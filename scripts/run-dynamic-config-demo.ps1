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

function Wait-RuntimeConfig([long]$Version, [string]$Status = "HEALTHY", [int]$Attempts = 35) {
    for ($i = 0; $i -lt $Attempts; $i++) {
        try {
            $state = Invoke-RestMethod "$gatewayUrl/runtime-config" -TimeoutSec 3
            if ($state.appliedVersion -eq $Version -and $state.status -eq $Status) { return $state }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    throw "gateway did not reach config version=$Version status=$Status"
}

function Update-Config([long]$Expected, [int]$NewPercent, [int]$ShadowPercent, [string]$Level, [string]$Actor) {
    $query = "expectedVersion=$Expected&newPipelinePercent=$NewPercent&shadowPercent=$ShadowPercent&degradationLevel=$Level&updatedBy=$Actor"
    return Invoke-RestMethod "$configUrl/api/config?$query" -Method Post -TimeoutSec 5
}

function Assert-HttpStatus([string]$Uri, [int]$ExpectedStatus) {
    try {
        $null = Invoke-WebRequest $Uri -Method Post -UseBasicParsing -TimeoutSec 5
        throw "request unexpectedly succeeded: $Uri"
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -ne $ExpectedStatus) {
            throw "expected HTTP $ExpectedStatus but got $($_.Exception.Response.StatusCode.value__)"
        }
    }
}

Push-Location $projectRoot
try {
    $env:COMPOSE_PROFILES = "dynamic"
    $env:CONFIG_CENTER_URL = "http://config-center:8888"
    if (Test-Image "eclipse-temurin:17-jre-jammy") { $env:MINI_RECO_RUNTIME_IMAGE = "eclipse-temurin:17-jre-jammy" }
    elseif (Test-Image "docker-ai:latest") { $env:MINI_RECO_RUNTIME_IMAGE = "docker-ai:latest" }

    Write-Host "[1/9] Running tests and packaging V18..."
    & mvn package
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }

    Write-Host "[2/9] Validating and building six-service Compose topology..."
    Invoke-Compose config --quiet
    Invoke-Compose build --pull=false
    Invoke-Compose down --volumes --remove-orphans
    $started = $true
    Invoke-Compose up -d --wait --wait-timeout 150
    $initial = Wait-RuntimeConfig 1

    Write-Host "[3/9] Publishing version 2: rollback primary and enable full shadow..."
    $v2 = Update-Config 1 0 100 "NONE" "release-bot"
    $state2 = Wait-RuntimeConfig 2
    $legacy = Invoke-RestMethod "$gatewayUrl/recommend?userId=101&scene=mall&limit=10"
    if ($legacy.debug.migration.primaryPipeline -ne "LEGACY" -or -not $legacy.debug.migration.shadowSelected) {
        throw "version 2 was not applied to real recommendation routing"
    }

    Write-Host "[4/9] Rejecting stale writer and invalid percentages..."
    Assert-HttpStatus "$configUrl/api/config?expectedVersion=1&newPipelinePercent=5&shadowPercent=0&degradationLevel=NONE&updatedBy=stale-writer" 409
    Assert-HttpStatus "$configUrl/api/config?expectedVersion=2&newPipelinePercent=101&shadowPercent=0&degradationLevel=NONE&updatedBy=bad-release" 400

    Write-Host "[5/9] Publishing version 3: stable 5% canary plus LIGHT degradation..."
    $v3 = Update-Config 2 5 20 "LIGHT" "release-bot"
    $state3 = Wait-RuntimeConfig 3
    $bucket0 = Invoke-RestMethod "$gatewayUrl/recommend?userId=100&scene=mall&limit=10"
    $bucket5 = Invoke-RestMethod "$gatewayUrl/recommend?userId=105&scene=mall&limit=10"
    $degraded = Invoke-RestMethod "$gatewayUrl/recommend?userId=199&scene=mall&limit=10"
    if ($bucket0.debug.migration.primaryPipeline -ne "NEW" `
            -or $bucket5.debug.migration.primaryPipeline -ne "LEGACY" `
            -or -not $degraded.debug.degradation.degraded `
            -or $degraded.debug.degradation.effectiveLimit -ne 8) {
        throw "stable bucket routing or degradation did not follow version 3"
    }

    Write-Host "[6/9] Pausing config center and proving last-known-good behavior..."
    Invoke-Compose pause config-center
    $stale = Wait-RuntimeConfig 3 "STALE" 25
    $stillServing = Invoke-RestMethod "$gatewayUrl/recommend?userId=100&scene=mall&limit=10"
    if ($stillServing.debug.migration.newPipelinePercent -ne 5 -or $stale.errorCount -lt 1) {
        throw "gateway did not retain last-known-good configuration"
    }

    Write-Host "[7/9] Unpausing config center and recovering polling..."
    Invoke-Compose unpause config-center
    $recovered = Wait-RuntimeConfig 3 "HEALTHY" 45

    Write-Host "[8/9] Publishing version 4 after recovery: full rollout..."
    $full = Update-Config 3 100 0 "NONE" "release-bot"
    $final = Wait-RuntimeConfig 4 "HEALTHY" 35
    $request = Invoke-RestMethod "$gatewayUrl/recommend?userId=199&scene=mall&limit=10"
    if ($request.debug.migration.primaryPipeline -ne "NEW" -or $request.debug.degradation.degraded) {
        throw "full rollout or degradation recovery failed"
    }

    Write-Host "[9/9] Verifying audit records..."
    $history = Invoke-RestMethod "$configUrl/api/config/history"
    if ($history.count -ne 4 -or $history.entries[0].updatedBy -ne "release-bot") {
        throw "config audit history assertion failed"
    }

    Write-Host "V18 DYNAMIC CONFIG ACCEPTANCE PASSED"
    Write-Host "versions=1->2->3->4, conflict=409, invalid=400, staleLastKnownGood=5%, recoveredFullRollout=$($final.lastKnownGood.newPipelinePercent)%"
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
