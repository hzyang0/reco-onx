param(
    [switch]$KeepRunning
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$baseUrl = "http://localhost:18093"
$traceUrl = "http://localhost:16686"
$started = $false
$previousRuntimeImage = $env:MINI_RECO_RUNTIME_IMAGE

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & docker compose @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Test-DockerImage {
    param([string]$Image)
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Docker and the JVM may write informational messages to stderr even on success.
        # PowerShell 7 turns those lines into ErrorRecord objects when Stop is enabled.
        $ErrorActionPreference = "Continue"
        & docker image inspect $Image 1>$null 2>$null
        return $LASTEXITCODE -eq 0
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Wait-HealthyResult {
    param([int]$MaxAttempts = 12)
    for ($i = 0; $i -lt $MaxAttempts; $i++) {
        $response = Invoke-RestMethod "$baseUrl/recommend?userId=123&scene=mall&limit=10"
        if ($response.debug.recallItemCount -eq 25 `
                -and $response.debug.resilience.goods.status -eq "SUCCESS" `
                -and $response.debug.resilience.live.status -eq "SUCCESS" `
                -and $response.debug.resilience.ad.status -eq "SUCCESS") {
            return $response
        }
        $null = Invoke-RestMethod "$baseUrl/resilience?reset=true"
        Start-Sleep -Milliseconds 500
    }
    throw "containerized gRPC recall did not reach a healthy 25-item result"
}

function Wait-LiveFallbackResult {
    for ($i = 0; $i -lt 10; $i++) {
        $response = Invoke-RestMethod "$baseUrl/recommend?userId=126&scene=mall&limit=10"
        if ($response.debug.recallItemCount -eq 17 `
                -and $response.debug.resilience.goods.status -eq "SUCCESS" `
                -and $response.debug.resilience.live.status -eq "FALLBACK" `
                -and $response.debug.resilience.ad.status -eq "SUCCESS") {
            return $response
        }
        # A request already in flight while Docker stops the container can also
        # consume the shared fan-out timeout. Retry until the remaining recalls settle.
        Start-Sleep -Milliseconds 500
    }
    throw "stopped live container did not settle at 17 goods + ad items"
}

function Wait-GrpcHealth {
    param([string]$Service, [string]$Target, [string]$GrpcService)
    for ($i = 0; $i -lt 30; $i++) {
        $previousErrorActionPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            & docker compose exec -T $Service java -cp /app/app.jar `
                com.interview.minireco.grpc.ops.GrpcHealthProbe $Target $GrpcService 1>$null 2>$null
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        if ($exitCode -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "$Service did not become gRPC healthy"
}

function Wait-Trace {
    param([string]$TraceId)
    for ($i = 0; $i -lt 40; $i++) {
        try {
            $trace = Invoke-RestMethod "$traceUrl/api/traces/$TraceId"
            $expected = @("mini-reco-ad", "mini-reco-gateway", "mini-reco-goods", "mini-reco-live")
            $actual = @($trace.serviceNames | Sort-Object)
            if ($trace.spanCount -ge 7 -and ($actual -join ",") -eq ($expected -join ",")) {
                return $trace
            }
        }
        catch {
            # BatchSpanProcessor and OTLP export are asynchronous.
        }
        Start-Sleep -Milliseconds 250
    }
    throw "trace $TraceId was not exported by all four application services"
}

Push-Location $projectRoot
try {
    Write-Host "[1/9] Running tests and packaging the application..."
    & mvn package
    if ($LASTEXITCODE -ne 0) {
        throw "mvn package failed with exit code $LASTEXITCODE"
    }

    if ([string]::IsNullOrWhiteSpace($env:MINI_RECO_RUNTIME_IMAGE)) {
        if (Test-DockerImage "eclipse-temurin:17-jre-jammy") {
            $env:MINI_RECO_RUNTIME_IMAGE = "eclipse-temurin:17-jre-jammy"
        }
        else {
            if (-not (Test-DockerImage "docker-ai:latest")) {
                throw "no Java 17 runtime image is available locally; set MINI_RECO_RUNTIME_IMAGE"
            }
            $env:MINI_RECO_RUNTIME_IMAGE = "docker-ai:latest"
            Write-Host "Docker Hub is unavailable; using cached Java 17 image docker-ai:latest"
        }
    }

    Write-Host "[2/9] Validating and building the Compose image..."
    Invoke-Compose config --quiet
    Invoke-Compose build --pull=false

    Write-Host "[3/9] Starting trace collector, three recalls and gateway..."
    Invoke-Compose down --volumes --remove-orphans
    Invoke-Compose up -d --wait --wait-timeout 120
    $started = $true

    Write-Host "[4/9] Checking gRPC reflection and standard health services..."
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $services = & docker compose exec -T goods java -cp /app/app.jar `
            com.interview.minireco.grpc.ops.GrpcReflectionClient localhost:19001 2>$null
        $reflectionExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($reflectionExitCode -ne 0 -or -not ($services -contains "mini_reco.goods.GoodsRecallRpc")) {
        throw "goods reflection did not list GoodsRecallRpc"
    }
    Wait-GrpcHealth "goods" "localhost:19001" "mini_reco.goods.GoodsRecallRpc"

    Write-Host "[5/9] Calling the gateway through Docker DNS-based gRPC targets..."
    $null = Wait-HealthyResult
    $http = Invoke-WebRequest "$baseUrl/recommend?userId=125&scene=mall&limit=10" -UseBasicParsing
    $healthy = $http.Content | ConvertFrom-Json
    $traceId = [string]@($http.Headers["X-Trace-Id"])[0]
    if ($healthy.debug.recallItemCount -ne 25 -or $healthy.debug.recallTransport -ne "grpc") {
        throw "healthy Compose request did not use all three gRPC recalls"
    }
    if ($traceId.Length -ne 32) {
        throw "gateway did not return a valid OpenTelemetry trace ID"
    }

    Write-Host "[6/9] Waiting for the cross-process OTLP trace..."
    $trace = Wait-Trace $traceId

    Write-Host "[7/9] Stopping live and verifying partial fallback..."
    Invoke-Compose stop live
    $failed = Wait-LiveFallbackResult

    Write-Host "[8/9] Restarting live, checking health and verifying recovery..."
    Invoke-Compose start live
    Wait-GrpcHealth "live" "localhost:19002" "mini_reco.live.LiveRecallRpc"
    $null = Invoke-RestMethod "$baseUrl/resilience?reset=true"
    Start-Sleep -Milliseconds 300
    # The restarted server can be healthy before the gateway's existing gRPC
    # channel finishes its exponential reconnect backoff.
    $recovered = Wait-HealthyResult -MaxAttempts 40

    Write-Host "[9/9] Docker Compose verification completed."
    @(
        [pscustomobject]@{
            Scenario = "healthy"
            RecallItems = $healthy.debug.recallItemCount
            LiveStatus = $healthy.debug.resilience.live.status
            TraceId = $traceId
            TraceSpans = $trace.spanCount
        },
        [pscustomobject]@{
            Scenario = "live_container_down"
            RecallItems = $failed.debug.recallItemCount
            LiveStatus = $failed.debug.resilience.live.status
            TraceId = "-"
            TraceSpans = "-"
        },
        [pscustomobject]@{
            Scenario = "live_recovered"
            RecallItems = $recovered.debug.recallItemCount
            LiveStatus = $recovered.debug.resilience.live.status
            TraceId = "-"
            TraceSpans = "-"
        }
    ) | Format-Table -AutoSize
    Write-Host "trace API:" "$traceUrl/api/traces/$traceId"
    Invoke-Compose ps
}
finally {
    if ($started -and -not $KeepRunning) {
        & docker compose down --volumes --remove-orphans
    }
    $env:MINI_RECO_RUNTIME_IMAGE = $previousRuntimeImage
    Pop-Location
}
