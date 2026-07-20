param(
    [switch]$KeepRunning
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$baseUrl = "http://localhost:18093"
$started = $false
$previousProfiles = $env:COMPOSE_PROFILES
$previousRedisUrl = $env:REDIS_URL
$previousConfigUrl = $env:CONFIG_CENTER_URL
$previousRuntimeImage = $env:MINI_RECO_RUNTIME_IMAGE

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

function Invoke-Recommend([long]$UserId) {
    for ($i = 0; $i -lt 20; $i++) {
        try {
            $response = Invoke-RestMethod "$baseUrl/recommend?userId=$UserId&scene=mall&limit=10" -TimeoutSec 10
            if ($response.items.Count -eq 10) { return $response }
        } catch {}
        try { $null = Invoke-RestMethod "$baseUrl/resilience?reset=true" -Method Post -TimeoutSec 3 } catch {}
        Start-Sleep -Milliseconds 500
    }
    throw "recommendation for user $UserId did not become healthy"
}

function Get-CacheStats {
    return Invoke-RestMethod "$baseUrl/feature-cache" -TimeoutSec 5
}

Push-Location $projectRoot
try {
    $env:COMPOSE_PROFILES = "cache"
    $env:REDIS_URL = "redis://redis:6379"
    $env:CONFIG_CENTER_URL = ""
    if (Test-Image "eclipse-temurin:17-jre-jammy") { $env:MINI_RECO_RUNTIME_IMAGE = "eclipse-temurin:17-jre-jammy" }
    elseif (Test-Image "docker-ai:latest") { $env:MINI_RECO_RUNTIME_IMAGE = "docker-ai:latest" }

    Write-Host "[1/8] Running 52 tests and packaging V19..."
    & mvn package
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }

    Write-Host "[2/8] Building and starting gateway, recalls, collector and real Redis..."
    Invoke-Compose config --quiet
    Invoke-Compose build --pull=false
    Invoke-Compose down --volumes --remove-orphans
    $started = $true
    Invoke-Compose up -d --wait --wait-timeout 150
    Invoke-Compose exec -T redis redis-cli FLUSHDB
    $null = Invoke-RestMethod "$baseUrl/feature-cache?reset=true" -Method Post

    Write-Host "[3/8] Proving cache-aside miss, origin load and write..."
    $first = Invoke-Recommend 777
    $afterMiss = Get-CacheStats
    if ($afterMiss.misses -ne 1 -or $afterMiss.originLoads -ne 1 -or $afterMiss.writes -ne 1) {
        throw "first request was not a single cache miss and origin load"
    }

    Write-Host "[4/8] Proving second request is a Redis hit..."
    $second = Invoke-Recommend 777
    $afterHit = Get-CacheStats
    if ($afterHit.hits -ne 1 -or $afterHit.originLoads -ne 1) {
        throw "second request did not hit Redis"
    }
    $ttlText = & docker compose exec -T redis redis-cli TTL mini-reco:user-feature:v1:777
    if ($LASTEXITCODE -ne 0) { throw "failed to query Redis TTL" }
    $ttl = [int]($ttlText | Select-Object -Last 1)
    if ($ttl -lt 45 -or $ttl -gt 75) { throw "unexpected jittered TTL: $ttl" }

    Write-Host "[5/8] Inspecting the actual Redis value and expiration..."
    $value = & docker compose exec -T redis redis-cli GET mini-reco:user-feature:v1:777
    if (($value -join "") -notmatch '^777\|') { throw "Redis does not contain encoded user feature" }

    Write-Host "[6/8] Stopping Redis and verifying fail-open origin fallback..."
    Invoke-Compose stop redis
    $beforeErrors = (Get-CacheStats).errors
    $duringOutage = Invoke-Recommend 778
    $outageStats = Get-CacheStats
    if ($duringOutage.items.Count -ne 10 -or $outageStats.errors -le $beforeErrors `
            -or $outageStats.originLoads -le $afterHit.originLoads) {
        throw "Redis outage did not fail open to the origin service"
    }

    Write-Host "[7/8] Restarting Redis and verifying automatic cache recovery..."
    Invoke-Compose start redis
    for ($i = 0; $i -lt 30; $i++) {
        try {
            $null = Invoke-Recommend 779
            $null = Invoke-Recommend 779
            $recoveryStats = Get-CacheStats
            if ($recoveryStats.hits -gt $afterHit.hits -and $recoveryStats.writes -gt $afterHit.writes) { break }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    if ($recoveryStats.hits -le $afterHit.hits -or $recoveryStats.writes -le $afterHit.writes) {
        throw "cache did not recover after Redis restart"
    }

    Write-Host "[8/8] Checking Prometheus-compatible cache counters..."
    $prometheus = (Invoke-WebRequest "$baseUrl/metrics/prometheus" -UseBasicParsing).Content
    if ($prometheus -notmatch 'mini_reco_feature_cache_access_total' `
            -or $prometheus -notmatch 'result="hit"' `
            -or $prometheus -notmatch 'result="error"') {
        throw "cache metrics are missing from Prometheus exposition"
    }

    Write-Host "V19 REDIS CACHE ACCEPTANCE PASSED"
    Write-Host "misses=$($recoveryStats.misses), hits=$($recoveryStats.hits), originLoads=$($recoveryStats.originLoads), errors=$($recoveryStats.errors), ttl=$ttl"
    if ($KeepRunning) { Write-Host "Services retained: gateway=$baseUrl Redis=localhost:16379" }
}
finally {
    if ($started -and -not $KeepRunning) { Invoke-Compose down --volumes --remove-orphans }
    $env:COMPOSE_PROFILES = $previousProfiles
    $env:REDIS_URL = $previousRedisUrl
    $env:CONFIG_CENTER_URL = $previousConfigUrl
    $env:MINI_RECO_RUNTIME_IMAGE = $previousRuntimeImage
    Pop-Location
}
