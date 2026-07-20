param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 18080
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot "target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar"
$stdoutPath = Join-Path $projectRoot "target/resilience-demo.out.log"
$stderrPath = Join-Path $projectRoot "target/resilience-demo.err.log"
$baseUrl = "http://localhost:$Port"
$process = $null

Push-Location $projectRoot
try {
    Write-Host "[1/4] Packaging application..."
    & mvn "-DskipTests" package
    if ($LASTEXITCODE -ne 0) {
        throw "mvn package failed with exit code $LASTEXITCODE"
    }

    Remove-Item $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    Write-Host "[2/4] Starting local service on port $Port..."
    $process = Start-Process -FilePath "java" `
        -ArgumentList "-jar", $jarPath, $Port `
        -WorkingDirectory $projectRoot `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -WindowStyle Hidden `
        -PassThru

    $ready = $false
    for ($i = 0; $i -lt 50; $i++) {
        if ($process.HasExited) {
            throw "service exited before becoming healthy"
        }
        try {
            $null = Invoke-RestMethod "$baseUrl/health" -TimeoutSec 1
            $ready = $true
            break
        }
        catch {
            Start-Sleep -Milliseconds 200
        }
    }
    if (-not $ready) {
        throw "service did not become healthy"
    }

    Write-Host "[3/4] Injecting live recall timeout and sending three requests..."
    $null = Invoke-RestMethod "$baseUrl/resilience?reset=true" -Method Post
    $null = Invoke-RestMethod "$baseUrl/resilience?source=live&fault=TIMEOUT" -Method Post
    $rows = @()
    for ($requestIndex = 1; $requestIndex -le 3; $requestIndex++) {
        $response = Invoke-RestMethod "$baseUrl/recommend?userId=123&scene=mall&limit=10"
        $live = $response.debug.resilience.live
        $fanout = $response.debug.recallFanout
        $rows += [pscustomobject]@{
            Request = $requestIndex
            TotalCostMs = $response.costMs
            ReturnedItems = $response.items.Count
            LiveStatus = $live.status
            Reason = $live.reason
            Attempts = $live.attempts
            Circuit = $live.circuit.state
            Fanout = $fanout.status
            RecallCostMs = $fanout.costMs
            FanoutTimedOut = [bool]($fanout.timedOutSources -contains "live")
        }
    }
    $rows | Format-Table -AutoSize

    if ($rows[0].Reason -ne "cancelled" -or $rows[0].Attempts -ne 2 -or -not $rows[0].FanoutTimedOut) {
        throw "first request should be cancelled by the fanout deadline during its retry"
    }
    if ($rows[1].Circuit -ne "OPEN") {
        throw "second failed request should open the circuit"
    }
    if ($rows[2].Reason -ne "circuit_open" -or $rows[2].Attempts -ne 0) {
        throw "third request should use fallback without calling live recall"
    }
    if (($rows | Where-Object { $_.ReturnedItems -ne 10 }).Count -gt 0) {
        throw "fallback should preserve 10 returned items"
    }

    $alerts = Invoke-RestMethod "$baseUrl/alerts"
    $fallbackAlert = $alerts.alerts | Where-Object { $_.rule -eq "downstream_fallback_happened" } | Select-Object -First 1
    if ($null -eq $fallbackAlert) {
        throw "fallback alert was not created"
    }
    Write-Host "fallback alert:" $fallbackAlert.rule

    Write-Host "[4/4] Recovering fault injection and circuit breaker..."
    $state = Invoke-RestMethod "$baseUrl/resilience?reset=true" -Method Post
    Write-Host "live fault:" $state.faultInjection.live
    Write-Host "live circuit:" $state.services.live.circuit.state
    Write-Host "service log:" $stdoutPath
}
finally {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
        Wait-Process -Id $process.Id -ErrorAction SilentlyContinue
    }
    Pop-Location
}
