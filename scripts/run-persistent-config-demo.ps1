param(
    [switch]$KeepRunning
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$gatewayUrl = "http://localhost:18093"
$configUrl = "http://localhost:18888"
$started = $false
$previousProfiles = $env:COMPOSE_PROFILES
$previousConfigUrl = $env:CONFIG_CENTER_URL
$previousRuntimeImage = $env:MINI_RECO_RUNTIME_IMAGE

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & docker compose @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose $($Arguments -join ' ') failed" }
}

function Test-Image([string]$Image) {
    $old = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & docker image inspect $Image 1>$null 2>$null
        return $LASTEXITCODE -eq 0
    } finally { $ErrorActionPreference = $old }
}

function Wait-Http([string]$Uri, [int]$Attempts = 60) {
    for ($i = 0; $i -lt $Attempts; $i++) {
        try { return Invoke-RestMethod $Uri -TimeoutSec 3 } catch {}
        Start-Sleep -Milliseconds 500
    }
    throw "service did not become ready: $Uri"
}

function Wait-RuntimeConfig([long]$Version, [string]$Status = "HEALTHY", [int]$Attempts = 60) {
    for ($i = 0; $i -lt $Attempts; $i++) {
        try {
            $state = Invoke-RestMethod "$gatewayUrl/runtime-config" -TimeoutSec 3
            if ($state.appliedVersion -eq $Version -and $state.status -eq $Status) { return $state }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    throw "gateway did not reach config version=$Version status=$Status"
}

function Update-Config([long]$Expected, [int]$NewPercent, [int]$ShadowPercent, [string]$Level) {
    $query = "expectedVersion=$Expected&newPipelinePercent=$NewPercent&shadowPercent=$ShadowPercent&degradationLevel=$Level&updatedBy=release-bot"
    return Invoke-RestMethod "$configUrl/api/config?$query" -Method Post -TimeoutSec 5
}

Push-Location $projectRoot
try {
    $env:COMPOSE_PROFILES = "dynamic"
    $env:CONFIG_CENTER_URL = "http://config-center:8888"
    if (Test-Image "eclipse-temurin:17-jre-jammy") { $env:MINI_RECO_RUNTIME_IMAGE = "eclipse-temurin:17-jre-jammy" }
    elseif (Test-Image "docker-ai:latest") { $env:MINI_RECO_RUNTIME_IMAGE = "docker-ai:latest" }

    Write-Host "[1/8] Running V21 tests and packaging..."
    & mvn package
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }

    Write-Host "[2/8] Building and starting the persistent configuration topology..."
    Invoke-Compose config --quiet
    Invoke-Compose build --pull=false
    Invoke-Compose down --volumes --remove-orphans
    $started = $true
    Invoke-Compose up -d --wait --wait-timeout 150
    $initial = Wait-RuntimeConfig 1

    Write-Host "[3/8] Publishing versions 2 and 3..."
    $null = Update-Config 1 5 20 "LIGHT"
    $null = Wait-RuntimeConfig 2
    $null = Update-Config 2 100 0 "NONE"
    $null = Wait-RuntimeConfig 3
    $beforeHistory = Invoke-RestMethod "$configUrl/api/config/history" -TimeoutSec 5
    if ($beforeHistory.count -ne 3) { throw "expected three audit entries before restart" }

    Write-Host "[4/8] Proving the configuration center uses a durable journal..."
    $health = Invoke-RestMethod "$configUrl/health" -TimeoutSec 5
    if ($health.version -ne 3 -or $health.storage -notlike "file:*config-journal.jsonl") {
        throw "configuration center is not backed by the expected file journal"
    }

    Write-Host "[5/8] Restarting the real configuration-center container..."
    Invoke-Compose restart config-center
    $afterRestart = Wait-Http "$configUrl/api/config"
    if ($afterRestart.version -ne 3 -or $afterRestart.newPipelinePercent -ne 100) {
        throw "configuration was lost after process restart"
    }
    $afterHistory = Invoke-RestMethod "$configUrl/api/config/history" -TimeoutSec 5
    if ($afterHistory.count -ne 3) { throw "audit history was lost after process restart" }

    Write-Host "[6/8] Verifying gateway polling recovers on version 3..."
    $null = Wait-RuntimeConfig 3 "HEALTHY" 90
    $recommendation = Invoke-RestMethod "$gatewayUrl/recommend?userId=101&scene=mall&limit=10" -TimeoutSec 5
    if ($recommendation.debug.migration.primaryPipeline -ne "NEW") {
        throw "gateway did not retain the persisted full-rollout configuration"
    }

    Write-Host "[7/8] Publishing version 4 after restart..."
    $null = Update-Config 3 0 0 "NONE"
    $finalState = Wait-RuntimeConfig 4
    $finalHistory = Invoke-RestMethod "$configUrl/api/config/history" -TimeoutSec 5
    if ($finalHistory.count -ne 4) { throw "journal could not continue appending after restart" }

    Write-Host "[8/8] V21 acceptance complete."
    Write-Host "V21 PERSISTENT CONFIG ACCEPTANCE PASSED"
    Write-Host "versions=1->2->3, restartRestored=3, appendAfterRestart=4, storage=$($health.storage)"
    if ($KeepRunning) {
        Write-Host "Services retained: gateway=$gatewayUrl config-center=$configUrl"
    }
}
finally {
    if ($started -and -not $KeepRunning) { Invoke-Compose down --volumes --remove-orphans }
    $env:COMPOSE_PROFILES = $previousProfiles
    $env:CONFIG_CENTER_URL = $previousConfigUrl
    $env:MINI_RECO_RUNTIME_IMAGE = $previousRuntimeImage
    Pop-Location
}
