param(
    [switch]$KeepRunning
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$gatewayUrl = "http://localhost:18093"
$configUrl = "http://localhost:18888"
$releaseKeyId = "release-bot"
$releaseSecret = "v22-demo-release-secret-2026"
$opsKeyId = "ops-bot"
$opsSecret = "v22-demo-operations-secret-2026"
$started = $false
$savedEnvironment = @{}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & docker compose @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose $($Arguments -join ' ') failed" }
}

function Save-Environment([string]$Name) {
    $savedEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, "Process")
}

function Restore-Environment {
    foreach ($entry in $savedEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }
}

function Test-Image([string]$Image) {
    $old = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & docker image inspect $Image 1>$null 2>$null
        return $LASTEXITCODE -eq 0
    } finally { $ErrorActionPreference = $old }
}

function New-SignedHeaders(
    [string]$Uri,
    [string]$KeyId,
    [string]$Secret,
    [long]$Timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds(),
    [string]$Nonce = ([Guid]::NewGuid().ToString("N"))
) {
    $parsed = [Uri]$Uri
    $query = $parsed.Query.TrimStart('?')
    $canonical = "POST`n$($parsed.AbsolutePath)`n$query`n$Timestamp`n$Nonce"
    $hmac = [System.Security.Cryptography.HMACSHA256]::new(
        [System.Text.Encoding]::UTF8.GetBytes($Secret)
    )
    try {
        $bytes = $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($canonical))
        $signature = [BitConverter]::ToString($bytes).Replace("-", "").ToLowerInvariant()
    } finally { $hmac.Dispose() }
    return @{
        "X-Admin-Key-Id" = $KeyId
        "X-Admin-Timestamp" = $Timestamp.ToString()
        "X-Admin-Nonce" = $Nonce
        "X-Admin-Signature" = $signature
    }
}

function Invoke-SignedPost([string]$Uri, [string]$KeyId, [string]$Secret) {
    $headers = New-SignedHeaders $Uri $KeyId $Secret
    return Invoke-RestMethod $Uri -Method Post -Headers $headers -TimeoutSec 5
}

function Assert-HttpStatus(
    [string]$Uri,
    [string]$Method,
    [int]$ExpectedStatus,
    [hashtable]$Headers = @{}
) {
    try {
        $response = Invoke-WebRequest $Uri -Method $Method -Headers $Headers -UseBasicParsing -TimeoutSec 5
        $actual = [int]$response.StatusCode
    } catch {
        if ($null -eq $_.Exception.Response) { throw }
        $actual = [int]$_.Exception.Response.StatusCode
    }
    if ($actual -ne $ExpectedStatus) {
        throw "expected HTTP $ExpectedStatus but got $actual for $Method $Uri"
    }
}

function Wait-Json([string]$Uri, [int]$Attempts = 80) {
    for ($i = 0; $i -lt $Attempts; $i++) {
        try { return Invoke-RestMethod $Uri -TimeoutSec 3 } catch {}
        Start-Sleep -Milliseconds 500
    }
    throw "service did not become ready: $Uri"
}

function Wait-RuntimeConfig([long]$Version, [int]$Attempts = 80) {
    for ($i = 0; $i -lt $Attempts; $i++) {
        try {
            $state = Invoke-RestMethod "$gatewayUrl/runtime-config" -TimeoutSec 3
            if ($state.appliedVersion -eq $Version -and $state.status -eq "HEALTHY") { return $state }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    throw "gateway did not apply healthy config version $Version"
}

function Wait-RecommendationCount([int]$Expected, [int]$Attempts = 60) {
    for ($i = 0; $i -lt $Attempts; $i++) {
        try {
            $result = Invoke-RestMethod "$gatewayUrl/recommend?userId=101&scene=mall&limit=30" -TimeoutSec 5
            if ($result.debug.recallItemCount -eq $Expected) { return $result }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    throw "recommendation count did not become $Expected"
}

Push-Location $projectRoot
try {
    foreach ($name in @(
        "COMPOSE_PROFILES", "CONFIG_CENTER_URL", "REDIS_URL", "MINI_RECO_RUNTIME_IMAGE",
        "ADMIN_RELEASE_KEY_ID", "ADMIN_RELEASE_SECRET", "ADMIN_OPS_KEY_ID", "ADMIN_OPS_SECRET",
        "ADMIN_AUTH_MAX_SKEW_SECONDS"
    )) { Save-Environment $name }

    $env:COMPOSE_PROFILES = "dynamic,cache"
    $env:CONFIG_CENTER_URL = "http://config-center:8888"
    $env:REDIS_URL = "redis://redis:6379"
    $env:ADMIN_RELEASE_KEY_ID = $releaseKeyId
    $env:ADMIN_RELEASE_SECRET = $releaseSecret
    $env:ADMIN_OPS_KEY_ID = $opsKeyId
    $env:ADMIN_OPS_SECRET = $opsSecret
    $env:ADMIN_AUTH_MAX_SKEW_SECONDS = "60"
    if (Test-Image "eclipse-temurin:17-jre-jammy") { $env:MINI_RECO_RUNTIME_IMAGE = "eclipse-temurin:17-jre-jammy" }
    elseif (Test-Image "docker-ai:latest") { $env:MINI_RECO_RUNTIME_IMAGE = "docker-ai:latest" }

    Write-Host "[1/10] Running the clean Maven release gate..."
    & mvn clean verify
    if ($LASTEXITCODE -ne 0) { throw "mvn clean verify failed" }

    Write-Host "[2/10] Validating Compose and building the V22 production image..."
    Invoke-Compose config --quiet
    Invoke-Compose build --pull=false
    Invoke-Compose down --volumes --remove-orphans
    $started = $true
    Invoke-Compose up -d --wait --wait-timeout 180
    $null = Wait-RuntimeConfig 1
    $authState = Invoke-RestMethod "$gatewayUrl/admin-auth" -TimeoutSec 5
    if (-not $authState.enabled -or $authState.keyIds.Count -ne 2) { throw "admin authentication is not enabled" }

    Write-Host "[3/10] Rejecting unsigned, forged, stale and replayed requests..."
    $configV2Uri = "$configUrl/api/config?expectedVersion=1&newPipelinePercent=5&shadowPercent=20&degradationLevel=NONE&updatedBy=release-bot"
    Assert-HttpStatus $configV2Uri "Post" 401
    $forged = New-SignedHeaders $configV2Uri $releaseKeyId "forged-secret-that-is-long-enough"
    Assert-HttpStatus $configV2Uri "Post" 401 $forged
    $stale = New-SignedHeaders $configV2Uri $releaseKeyId $releaseSecret ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - 120)
    Assert-HttpStatus $configV2Uri "Post" 401 $stale
    $replayHeaders = New-SignedHeaders $configV2Uri $releaseKeyId $releaseSecret
    $published = Invoke-RestMethod $configV2Uri -Method Post -Headers $replayHeaders -TimeoutSec 5
    if ($published.version -ne 2) { throw "signed release did not publish version 2" }
    Assert-HttpStatus $configV2Uri "Post" 401 $replayHeaders
    $null = Wait-RuntimeConfig 2

    Write-Host "[4/10] Enforcing RELEASE and OPS role boundaries..."
    $configV3Uri = "$configUrl/api/config?expectedVersion=2&newPipelinePercent=100&shadowPercent=0&degradationLevel=NONE&updatedBy=ops-bot"
    $opsOnConfig = New-SignedHeaders $configV3Uri $opsKeyId $opsSecret
    Assert-HttpStatus $configV3Uri "Post" 403 $opsOnConfig
    $rolloutUri = "$gatewayUrl/rollout?newPercent=5&shadowPercent=0"
    $rollout = Invoke-SignedPost $rolloutUri $releaseKeyId $releaseSecret
    if ($rollout.config.newPipelinePercent -ne 5) { throw "release role could not change rollout" }
    $degradationUri = "$gatewayUrl/degradation?level=LIGHT"
    $degradation = Invoke-SignedPost $degradationUri $opsKeyId $opsSecret
    if ($degradation.level -ne "LIGHT") { throw "ops role could not change degradation" }
    Assert-HttpStatus "$gatewayUrl/degradation?level=HEAVY" "Get" 405
    $null = Invoke-SignedPost "$gatewayUrl/degradation?level=NONE" $opsKeyId $opsSecret

    Write-Host "[5/10] Proving V21 persistence remains intact under V22 auth..."
    Invoke-Compose restart config-center
    $restored = Wait-Json "$configUrl/api/config"
    if ($restored.version -ne 2) { throw "persisted authenticated release was lost after restart" }
    $history = Invoke-RestMethod "$configUrl/api/config/history" -TimeoutSec 5
    if ($history.count -ne 2) { throw "authenticated release audit history was lost" }
    $null = Wait-RuntimeConfig 2

    Write-Host "[6/10] Proving the Redis dependency fails open and recovers..."
    $null = Invoke-RestMethod "$gatewayUrl/recommend?userId=901&scene=mall&limit=10" -TimeoutSec 5
    Invoke-Compose stop redis
    $redisOutage = Invoke-RestMethod "$gatewayUrl/recommend?userId=902&scene=mall&limit=10" -TimeoutSec 5
    if ($redisOutage.items.Count -lt 1) { throw "recommendation failed during Redis outage" }
    Invoke-Compose start redis

    Write-Host "[7/10] Proving a live-recall outage degrades instead of taking down the gateway..."
    Invoke-Compose stop live
    $fallback = Wait-RecommendationCount 17
    if ($fallback.debug.recallItemCount -ne 17) { throw "live outage did not produce the expected partial result" }

    Write-Host "[8/10] Recovering live recall back to the full result..."
    Invoke-Compose start live
    $recovered = Wait-RecommendationCount 25 90

    Write-Host "[9/10] Checking health, read-only endpoints and persisted storage..."
    $gatewayHealth = Invoke-RestMethod "$gatewayUrl/health" -TimeoutSec 5
    $configHealth = Invoke-RestMethod "$configUrl/health" -TimeoutSec 5
    $rolloutView = Invoke-RestMethod "$gatewayUrl/rollout" -TimeoutSec 5
    if ($gatewayHealth.status -ne "UP" `
            -or $configHealth.status -ne "UP" `
            -or -not $configHealth.adminAuthEnabled `
            -or $configHealth.storage -notlike "file:*") {
        throw "final health or storage assertion failed"
    }

    Write-Host "[10/10] V22 final engineering acceptance complete."
    Write-Host "V22 FINAL ACCEPTANCE PASSED"
    Write-Host "tests=65, auth=HMAC+RBAC+timestamp+nonce, persistence=restored-v2, redis=fail-open, live=25->17->25"
    if ($KeepRunning) {
        Write-Host "Services retained: gateway=$gatewayUrl config-center=$configUrl redis=localhost:16379"
    }
}
finally {
    if ($started -and -not $KeepRunning) { Invoke-Compose down --volumes --remove-orphans }
    Restore-Environment
    Pop-Location
}
