param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 18084
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot "target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar"
$stdoutPath = Join-Path $projectRoot "target/parallel-recall-demo.out.log"
$stderrPath = Join-Path $projectRoot "target/parallel-recall-demo.err.log"
$baseUrl = "http://localhost:$Port"
$process = $null

Push-Location $projectRoot
try {
    Write-Host "[1/5] Packaging application..."
    & mvn "-DskipTests" package
    if ($LASTEXITCODE -ne 0) {
        throw "mvn package failed with exit code $LASTEXITCODE"
    }

    Remove-Item $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    Write-Host "[2/5] Starting local service on port $Port..."
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

    Write-Host "[3/5] Running healthy parallel fan-out..."
    $null = Invoke-RestMethod "$baseUrl/resilience?reset=true" -Method Post
    $normal = Invoke-RestMethod "$baseUrl/recommend?userId=123&scene=mall&limit=10"
    $normalFanout = $normal.debug.recallFanout
    $sourceCostSum = ($normalFanout.sourceCostMs.PSObject.Properties.Value | Measure-Object -Sum).Sum

    if ($normalFanout.status -ne "SUCCESS" -or $normalFanout.completedSources.Count -ne 3) {
        throw "all three recall sources should complete in the healthy case"
    }
    if ($normalFanout.costMs -ge $sourceCostSum) {
        throw "fan-out cost should be lower than the sum of source costs"
    }

    Write-Host "[4/5] Injecting live timeout and checking partial results..."
    $null = Invoke-RestMethod "$baseUrl/resilience?source=live&fault=TIMEOUT" -Method Post
    $partial = Invoke-RestMethod "$baseUrl/recommend?userId=123&scene=mall&limit=10"
    $partialFanout = $partial.debug.recallFanout

    if ($partialFanout.status -ne "PARTIAL" -or -not ($partialFanout.timedOutSources -contains "live")) {
        throw "live recall should be cut off by the overall fan-out deadline"
    }
    if ($partial.items.Count -ne 10) {
        throw "partial recall should still return 10 final items through fallback"
    }
    $alerts = Invoke-RestMethod "$baseUrl/alerts"
    $fanoutAlert = $alerts.alerts | Where-Object { $_.rule -eq "recall_fanout_timeout" } | Select-Object -First 1
    if ($null -eq $fanoutAlert) {
        throw "fan-out timeout alert was not created"
    }

    @(
        [pscustomobject]@{
            Mode = "healthy"
            TotalCostMs = $normal.costMs
            RecallCostMs = $normalFanout.costMs
            SourceCostSumMs = $sourceCostSum
            FanoutStatus = $normalFanout.status
            Completed = $normalFanout.completedSources -join "+"
            TimedOut = "-"
            RecallItems = $normal.debug.recallItemCount
            ReturnedItems = $normal.items.Count
        },
        [pscustomobject]@{
            Mode = "live_timeout"
            TotalCostMs = $partial.costMs
            RecallCostMs = $partialFanout.costMs
            SourceCostSumMs = "-"
            FanoutStatus = $partialFanout.status
            Completed = $partialFanout.completedSources -join "+"
            TimedOut = $partialFanout.timedOutSources -join "+"
            RecallItems = $partial.debug.recallItemCount
            ReturnedItems = $partial.items.Count
        }
    ) | Format-Table -AutoSize
    Write-Host "fan-out alert:" $fanoutAlert.rule

    Write-Host "[5/5] Resetting injected fault..."
    $null = Invoke-RestMethod "$baseUrl/resilience?reset=true" -Method Post
    Write-Host "service log:" $stdoutPath
}
finally {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
        Wait-Process -Id $process.Id -ErrorAction SilentlyContinue
    }
    Pop-Location
}
