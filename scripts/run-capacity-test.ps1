param(
    [switch]$KeepRunning
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$baseUrl = "http://localhost:18093"
$reportDir = "target/capacity-v20"
$strictReportDir = "target/capacity-v20-strict-gate"
$started = $false
$saved = @{}
foreach ($name in @("COMPOSE_PROFILES", "REDIS_URL", "CONFIG_CENTER_URL", "MINI_RECO_RUNTIME_IMAGE",
        "SLO_MIN_SUCCESS_RATE", "SLO_MAX_FALLBACK_RATE", "SLO_MAX_P95_MS", "ALLOW_SLO_FAILURE",
        "GRPC_DEADLINE_MS", "RECALL_TIMEOUT_MS", "RECALL_FANOUT_TIMEOUT_MS")) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name)
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & docker compose @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose $($Arguments -join ' ') failed" }
}

function Test-Image([string]$Image) {
    $old = $ErrorActionPreference
    try { $ErrorActionPreference = "Continue"; & docker image inspect $Image 1>$null 2>$null; return $LASTEXITCODE -eq 0 }
    finally { $ErrorActionPreference = $old }
}

function Wait-HealthyRecommendation([int]$Attempts = 30) {
    for ($i = 0; $i -lt $Attempts; $i++) {
        try {
            $response = Invoke-RestMethod "$baseUrl/recommend?userId=10001&scene=mall&limit=10" -TimeoutSec 8
            if ($response.debug.recallItemCount -eq 25 `
                    -and $response.debug.resilience.goods.status -eq "SUCCESS" `
                    -and $response.debug.resilience.live.status -eq "SUCCESS" `
                    -and $response.debug.resilience.ad.status -eq "SUCCESS") { return $response }
        } catch {}
        try { $null = Invoke-RestMethod "$baseUrl/resilience?reset=true" -Method Post -TimeoutSec 3 } catch {}
        Start-Sleep -Milliseconds 500
    }
    throw "recommendation service did not reach a fully healthy state"
}

Push-Location $projectRoot
try {
    $env:COMPOSE_PROFILES = "cache,dynamic"
    $env:REDIS_URL = "redis://redis:6379"
    $env:CONFIG_CENTER_URL = "http://config-center:8888"
    $env:GRPC_DEADLINE_MS = "100"
    $env:RECALL_TIMEOUT_MS = "120"
    $env:RECALL_FANOUT_TIMEOUT_MS = "150"
    if (Test-Image "eclipse-temurin:17-jre-jammy") { $env:MINI_RECO_RUNTIME_IMAGE = "eclipse-temurin:17-jre-jammy" }
    elseif (Test-Image "docker-ai:latest") { $env:MINI_RECO_RUNTIME_IMAGE = "docker-ai:latest" }

    Write-Host "[1/10] Running all 54 tests and packaging final V20..."
    & mvn package
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }

    Write-Host "[2/10] Building and starting the full seven-container stack..."
    Invoke-Compose config --quiet
    Invoke-Compose build --pull=false
    Invoke-Compose down --volumes --remove-orphans
    $started = $true
    Invoke-Compose up -d --wait --wait-timeout 180
    $null = Wait-HealthyRecommendation

    Write-Host "[3/10] Warming Redis and runtime paths..."
    for ($i = 0; $i -lt 30; $i++) {
        $null = Invoke-RestMethod "$baseUrl/recommend?userId=$($i + 10000)&scene=mall&limit=10" -TimeoutSec 8
    }
    $null = Invoke-RestMethod "$baseUrl/resilience?reset=true" -Method Post -TimeoutSec 3

    Write-Host "[4/10] Running 1/2/4/8 concurrency capacity staircase..."
    $env:SLO_MIN_SUCCESS_RATE = "99"
    $env:SLO_MAX_FALLBACK_RATE = "5"
    $env:SLO_MAX_P95_MS = "1200"
    & java -cp target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar `
        com.interview.minireco.capacity.CapacityBenchmarkApplication `
        $baseUrl 2 8 "1,2,4,8" $reportDir
    if ($LASTEXITCODE -ne 0) { throw "no tested concurrency level satisfied the V20 SLO" }

    Write-Host "[5/10] Validating JSON, CSV and Markdown capacity artifacts..."
    $summary = Get-Content "$reportDir/summary.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($summary.results.Count -ne 4 -or $summary.maxPassingConcurrency -lt 1 `
            -or -not (Test-Path "$reportDir/results.csv") -or -not (Test-Path "$reportDir/report.md")) {
        throw "capacity report artifacts are incomplete"
    }

    Write-Host "[6/10] Proving an impossible latency budget fails the SLO gate..."
    $env:SLO_MAX_P95_MS = "1"
    $env:ALLOW_SLO_FAILURE = "true"
    & java -cp target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar `
        com.interview.minireco.capacity.CapacityBenchmarkApplication `
        $baseUrl 1 3 "1" $strictReportDir
    if ($LASTEXITCODE -ne 0) { throw "strict SLO diagnostic run crashed" }
    $strict = Get-Content "$strictReportDir/summary.json" -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($strict.maxPassingConcurrency -ne 0 -or $strict.results[0].sloPass) {
        throw "impossible P95 budget did not fail the quality gate"
    }

    Write-Host "[7/10] Injecting a live-recall outage and checking quality fallback visibility..."
    Invoke-Compose stop live
    $fallbackObserved = $false
    for ($i = 0; $i -lt 12; $i++) {
        $response = Invoke-RestMethod "$baseUrl/recommend?userId=$($i + 12000)&scene=mall&limit=10" -TimeoutSec 8
        if ($response.debug.resilience.live.status -eq "FALLBACK" -and $response.items.Count -eq 10) {
            $fallbackObserved = $true
            break
        }
    }
    if (-not $fallbackObserved) { throw "live outage was not visible as fallback quality loss" }

    Write-Host "[8/10] Restoring live and rechecking full recommendation quality..."
    Invoke-Compose start live
    $recovered = Wait-HealthyRecommendation 50

    Write-Host "[9/10] Capturing final container resource snapshot..."
    & docker stats --no-stream --format "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}" | Sort-Object
    if ($LASTEXITCODE -ne 0) { throw "docker stats failed" }

    Write-Host "[10/10] Final acceptance summary..."
    $best = @($summary.results | Where-Object sloPass | Sort-Object concurrency -Descending)[0]
    Write-Host "V20 FINAL CAPACITY ACCEPTANCE PASSED"
    Write-Host "maxPassingConcurrency=$($summary.maxPassingConcurrency), bestQps=$($best.throughputQps), p95Ms=$($best.p95Ms), success=$($best.successRatePercent)%, fallback=$($best.fallbackRatePercent)%"
    Write-Host "reports=$((Resolve-Path $reportDir).Path), strictGateDetected=true, faultRecoveryRecallItems=$($recovered.debug.recallItemCount)"
    if ($KeepRunning) { Write-Host "Full stack retained at $baseUrl" }
}
finally {
    if ($started -and -not $KeepRunning) { Invoke-Compose down --volumes --remove-orphans }
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name]) }
    Pop-Location
}
