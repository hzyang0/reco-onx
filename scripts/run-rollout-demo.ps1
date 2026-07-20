param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 18090
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot "target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar"
$stdoutPath = Join-Path $projectRoot "target/rollout-demo.out.log"
$stderrPath = Join-Path $projectRoot "target/rollout-demo.err.log"
$baseUrl = "http://localhost:$Port"
$process = $null

function Wait-ComparisonCount {
    param([long]$Expected)
    for ($i = 0; $i -lt 60; $i++) {
        $state = Invoke-RestMethod "$baseUrl/rollout"
        if ($state.comparison.summary.totalComparisons -ge $Expected) {
            return $state
        }
        Start-Sleep -Milliseconds 100
    }
    throw "shadow comparison count did not reach $Expected"
}

Push-Location $projectRoot
try {
    Write-Host "[1/6] Packaging application..."
    & mvn "-DskipTests" package
    if ($LASTEXITCODE -ne 0) {
        throw "mvn package failed with exit code $LASTEXITCODE"
    }

    Remove-Item $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    Write-Host "[2/6] Starting local service on port $Port..."
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

    Write-Host "[3/6] Running legacy primary with 100% new-pipeline shadow..."
    $null = Invoke-RestMethod "$baseUrl/rollout?newPercent=0&shadowPercent=100&clear=true" -Method Post
    $userIds = @(123, 124, 125)
    for ($index = 0; $index -lt $userIds.Count; $index++) {
        $response = Invoke-RestMethod "$baseUrl/recommend?userId=$($userIds[$index])&scene=mall&limit=10"
        if ($response.debug.migration.primaryPipeline -ne "LEGACY") {
            throw "shadow stage must keep LEGACY as the user-facing pipeline"
        }
        if ($response.debug.migration.shadowPipeline -ne "NEW") {
            throw "NEW pipeline should run as shadow"
        }
        $null = Wait-ComparisonCount ($index + 1)
    }

    $shadowState = Invoke-RestMethod "$baseUrl/rollout"
    $summary = $shadowState.comparison.summary
    if ($summary.totalComparisons -ne 3 -or $summary.exactMatches -ne 3) {
        throw "all three shadow comparisons should match exactly"
    }
    if ($summary.mismatches -ne 0 -or $summary.shadowErrors -ne 0) {
        throw "shadow stage should have no mismatch or error"
    }
    [pscustomobject]@{
        Comparisons = $summary.totalComparisons
        ExactMatches = $summary.exactMatches
        ExactRate = $summary.exactMatchRate
        AvgOverlap = $summary.averageOverlapRate
        LegacyAvgMs = $summary.averageLegacyCostMs
        NewAvgMs = $summary.averageNewCostMs
        AvgSavingMs = $summary.averageCostSavingMs
    } | Format-Table -AutoSize

    Write-Host "[4/6] Switching to a 5% canary..."
    $null = Invoke-RestMethod "$baseUrl/rollout?newPercent=5&shadowPercent=0" -Method Post
    $bucket0 = Invoke-RestMethod "$baseUrl/recommend?userId=100&scene=mall&limit=10"
    $bucket5 = Invoke-RestMethod "$baseUrl/recommend?userId=105&scene=mall&limit=10"
    if ($bucket0.debug.migration.primaryPipeline -ne "NEW") {
        throw "bucket 0 should enter the 5% NEW canary"
    }
    if ($bucket5.debug.migration.primaryPipeline -ne "LEGACY") {
        throw "bucket 5 should remain on LEGACY at a 5% canary"
    }
    @(
        [pscustomobject]@{ UserId = 100; Bucket = 0; Pipeline = $bucket0.debug.migration.primaryPipeline },
        [pscustomobject]@{ UserId = 105; Bucket = 5; Pipeline = $bucket5.debug.migration.primaryPipeline }
    ) | Format-Table -AutoSize

    Write-Host "[5/6] Promoting NEW pipeline to 100%..."
    $fullState = Invoke-RestMethod "$baseUrl/rollout?newPercent=100&shadowPercent=0" -Method Post
    $fullResponse = Invoke-RestMethod "$baseUrl/recommend?userId=199&scene=mall&limit=10"
    if ($fullState.config.newPipelinePercent -ne 100 -or $fullResponse.debug.migration.primaryPipeline -ne "NEW") {
        throw "full rollout should route every user to NEW"
    }

    Write-Host "[6/6] Rollout verification completed."
    Write-Host "new pipeline percent:" $fullState.config.newPipelinePercent
    Write-Host "service log:" $stdoutPath
}
finally {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
        Wait-Process -Id $process.Id -ErrorAction SilentlyContinue
    }
    Pop-Location
}
