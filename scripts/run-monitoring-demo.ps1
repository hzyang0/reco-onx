param(
    [switch]$KeepRunning
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$baseUrl = "http://localhost:18093"
$prometheusUrl = "http://localhost:19090"
$grafanaUrl = "http://localhost:13000"
$started = $false
$previousRuntimeImage = $env:MINI_RECO_RUNTIME_IMAGE
$previousProfiles = $env:COMPOSE_PROFILES

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
        $ErrorActionPreference = "Continue"
        & docker image inspect $Image 1>$null 2>$null
        return $LASTEXITCODE -eq 0
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Assert-MonitoringImages {
    foreach ($image in @("prom/prometheus:v2.50.0", "grafana/grafana:10.4.2")) {
        if (-not (Test-DockerImage $image)) {
            Write-Host "Pulling missing monitoring image $image..."
            & docker pull $image
            if ($LASTEXITCODE -ne 0) {
                throw "failed to pull required monitoring image $image"
            }
        }
    }
}

function Wait-HealthyResult {
    param([int]$MaxAttempts = 15)
    for ($i = 0; $i -lt $MaxAttempts; $i++) {
        $response = Invoke-RestMethod "$baseUrl/recommend?userId=223&scene=mall&limit=10"
        if ($response.debug.recallItemCount -eq 25 `
                -and $response.debug.resilience.goods.status -eq "SUCCESS" `
                -and $response.debug.resilience.live.status -eq "SUCCESS" `
                -and $response.debug.resilience.ad.status -eq "SUCCESS") {
            return $response
        }
        $null = Invoke-RestMethod "$baseUrl/resilience?reset=true" -Method Post
        Start-Sleep -Milliseconds 500
    }
    throw "gRPC recalls did not settle at 25 healthy items"
}

function Wait-LiveFallbackResult {
    for ($i = 0; $i -lt 12; $i++) {
        $response = Invoke-RestMethod "$baseUrl/recommend?userId=226&scene=mall&limit=10"
        if ($response.debug.recallItemCount -eq 17 `
                -and $response.debug.resilience.goods.status -eq "SUCCESS" `
                -and $response.debug.resilience.live.status -eq "FALLBACK" `
                -and $response.debug.resilience.ad.status -eq "SUCCESS") {
            return $response
        }
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

function Get-PrometheusResult {
    param([string]$Query)
    $encodedQuery = [uri]::EscapeDataString($Query)
    $response = Invoke-RestMethod "$prometheusUrl/api/v1/query?query=$encodedQuery"
    if ($response.status -ne "success") {
        throw "Prometheus query failed: $Query"
    }
    return @($response.data.result)
}

function Wait-PrometheusScalar {
    param(
        [string]$Query,
        [scriptblock]$Accept,
        [string]$Description,
        [int]$MaxAttempts = 40
    )
    for ($i = 0; $i -lt $MaxAttempts; $i++) {
        try {
            $result = @(Get-PrometheusResult $Query)
            if ($result.Count -gt 0) {
                $value = [double]$result[0].value[1]
                if (& $Accept $value) {
                    return $value
                }
            }
        }
        catch {
            # Prometheus may still be warming up or evaluating the first scrape.
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Prometheus did not observe $Description; query=$Query"
}

function Wait-PrometheusSeriesCount {
    param(
        [string]$Query,
        [int]$ExpectedCount,
        [string]$Description,
        [int]$MaxAttempts = 40
    )
    for ($i = 0; $i -lt $MaxAttempts; $i++) {
        try {
            $result = @(Get-PrometheusResult $Query)
            if ($result.Count -eq $ExpectedCount) {
                return $result.Count
            }
        }
        catch {
            # Alert evaluation is eventually consistent with scrape results.
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Prometheus did not observe $Description; query=$Query"
}

Push-Location $projectRoot
try {
    $env:COMPOSE_PROFILES = "monitoring"

    Write-Host "[1/10] Running tests and packaging the application..."
    & mvn package
    if ($LASTEXITCODE -ne 0) {
        throw "mvn package failed with exit code $LASTEXITCODE"
    }

    Assert-MonitoringImages
    if ([string]::IsNullOrWhiteSpace($env:MINI_RECO_RUNTIME_IMAGE)) {
        if (Test-DockerImage "eclipse-temurin:17-jre-jammy") {
            $env:MINI_RECO_RUNTIME_IMAGE = "eclipse-temurin:17-jre-jammy"
        }
        elseif (Test-DockerImage "docker-ai:latest") {
            $env:MINI_RECO_RUNTIME_IMAGE = "docker-ai:latest"
            Write-Host "Docker Hub is unavailable; using cached Java 17 image docker-ai:latest"
        }
        else {
            $env:MINI_RECO_RUNTIME_IMAGE = "eclipse-temurin:17-jre-jammy"
            Write-Host "Java 17 runtime image is not cached; Docker will pull eclipse-temurin:17-jre-jammy"
        }
    }

    Write-Host "[2/10] Validating Prometheus rules, Compose and Grafana dashboard JSON..."
    Get-Content monitoring/grafana/dashboards/mini-reco-overview.json -Raw -Encoding UTF8 |
        ConvertFrom-Json | Out-Null
    & docker run --rm --entrypoint promtool `
        -v "${projectRoot}/monitoring/prometheus:/etc/prometheus:ro" `
        prom/prometheus:v2.50.0 check config /etc/prometheus/prometheus.yml
    if ($LASTEXITCODE -ne 0) {
        throw "promtool configuration validation failed with exit code $LASTEXITCODE"
    }
    Invoke-Compose config --quiet

    Write-Host "[3/10] Building the V16 application image..."
    Invoke-Compose build --pull=false

    Write-Host "[4/10] Starting seven containers and waiting for health checks..."
    Invoke-Compose down --volumes --remove-orphans
    $started = $true
    Invoke-Compose up -d --wait --wait-timeout 150

    Write-Host "[5/10] Generating healthy traffic for gateway and recall metrics..."
    $healthy = Wait-HealthyResult
    for ($i = 0; $i -lt 20; $i++) {
        $null = Invoke-RestMethod "$baseUrl/recommend?userId=$($i + 300)&scene=mall&limit=10"
    }
    $exposition = Invoke-WebRequest "$baseUrl/metrics/prometheus" -UseBasicParsing
    if ($exposition.Headers["Content-Type"] -notlike "text/plain*" `
            -or $exposition.Content -notmatch "# TYPE mini_reco_request_cost_seconds histogram") {
        throw "gateway did not expose Prometheus histogram text format"
    }

    Write-Host "[6/10] Querying real Prometheus time series and loaded rules..."
    $healthyTargets = Wait-PrometheusScalar `
        'sum(up{job=~"mini-reco-.+"})' `
        { param($value) $value -eq 4 } `
        "four healthy application scrape targets"
    $requestCount = Wait-PrometheusScalar `
        'sum(mini_reco_request_success_total)' `
        { param($value) $value -ge 20 } `
        "at least twenty successful recommendation requests"
    $grpcServerCalls = Wait-PrometheusScalar `
        'sum(mini_reco_grpc_server_call_total)' `
        { param($value) $value -ge 60 } `
        "at least sixty downstream gRPC server calls"
    $rules = Invoke-RestMethod "$prometheusUrl/api/v1/rules"
    $ruleCount = @($rules.data.groups.rules).Count
    if ($rules.status -ne "success" -or $ruleCount -lt 3) {
        throw "Prometheus did not load all three V16 alert rules"
    }

    Write-Host "[7/10] Verifying the provisioned Grafana datasource and dashboard..."
    $grafanaHealth = Invoke-RestMethod "$grafanaUrl/api/health"
    $datasourceHealth = Invoke-RestMethod "$grafanaUrl/api/datasources/uid/prometheus/health"
    $dashboard = Invoke-RestMethod "$grafanaUrl/api/dashboards/uid/mini-reco-overview"
    if ($grafanaHealth.database -ne "ok" `
            -or $datasourceHealth.status -ne "OK" `
            -or $dashboard.dashboard.title -ne "Mini Reco Access Layer Overview" `
            -or @($dashboard.dashboard.panels).Count -ne 7) {
        throw "Grafana provisioning verification failed"
    }

    Write-Host "[8/10] Stopping live and waiting for target-down alert plus business fallback..."
    Invoke-Compose stop live
    $failed = Wait-LiveFallbackResult
    $null = Wait-PrometheusScalar `
        'up{job="mini-reco-recall",instance="live:19102"}' `
        { param($value) $value -eq 0 } `
        "live scrape target down"
    $firingAlerts = Wait-PrometheusSeriesCount `
        'ALERTS{alertname="MiniRecoTargetDown",alertstate="firing",instance="live:19102"}' `
        1 `
        "a firing target-down alert" `
        50

    Write-Host "[9/10] Restarting live and verifying monitoring plus business recovery..."
    Invoke-Compose start live
    Wait-GrpcHealth "live" "localhost:19002" "mini_reco.live.LiveRecallRpc"
    $null = Invoke-RestMethod "$baseUrl/resilience?reset=true" -Method Post
    $recovered = Wait-HealthyResult -MaxAttempts 50
    $null = Wait-PrometheusScalar `
        'up{job="mini-reco-recall",instance="live:19102"}' `
        { param($value) $value -eq 1 } `
        "live scrape target recovered"
    $null = Wait-PrometheusSeriesCount `
        'ALERTS{alertname="MiniRecoTargetDown",alertstate="firing",instance="live:19102"}' `
        0 `
        "the target-down alert resolved"

    Write-Host "[10/10] V16 monitoring verification completed."
    @(
        [pscustomobject]@{
            Scenario = "healthy"
            RecallItems = $healthy.debug.recallItemCount
            LiveStatus = $healthy.debug.resilience.live.status
            PrometheusUp = $healthyTargets
            AlertFiring = "no"
        },
        [pscustomobject]@{
            Scenario = "live_container_down"
            RecallItems = $failed.debug.recallItemCount
            LiveStatus = $failed.debug.resilience.live.status
            PrometheusUp = 3
            AlertFiring = $firingAlerts -eq 1
        },
        [pscustomobject]@{
            Scenario = "live_recovered"
            RecallItems = $recovered.debug.recallItemCount
            LiveStatus = $recovered.debug.resilience.live.status
            PrometheusUp = 4
            AlertFiring = "no"
        }
    ) | Format-Table -AutoSize
    Write-Host "request success samples:" $requestCount
    Write-Host "gRPC server call samples:" $grpcServerCalls
    Write-Host "Prometheus:" $prometheusUrl
    Write-Host "Grafana dashboard:" "$grafanaUrl/d/mini-reco-overview"
    Invoke-Compose ps
}
finally {
    if ($started -and -not $KeepRunning) {
        & docker compose down --volumes --remove-orphans
    }
    $env:MINI_RECO_RUNTIME_IMAGE = $previousRuntimeImage
    $env:COMPOSE_PROFILES = $previousProfiles
    Pop-Location
}
