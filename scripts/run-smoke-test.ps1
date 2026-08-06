$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root "target"
$jar = Join-Path $target "mini-reco-access-layer-0.1.0-SNAPSHOT-all.jar"
$stdout = Join-Path $target "smoke-test.out.log"
$stderr = Join-Path $target "smoke-test.err.log"
$portProbe = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback,
        0
)
$portProbe.Start()
$port = ([System.Net.IPEndPoint]$portProbe.LocalEndpoint).Port
$portProbe.Stop()

if (-not (Test-NetConnection -ComputerName "localhost" -Port 3307 -InformationLevel Quiet)) {
    throw "MySQL is not reachable on localhost:3307. Run 'docker compose up -d db' first."
}

if (-not (Test-Path -LiteralPath $jar)) {
    throw "Missing application JAR. Run 'mvn -DskipTests package' first."
}

Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue

$process = Start-Process `
        -FilePath "java" `
        -ArgumentList @("-jar", $jar, "$port") `
        -WindowStyle Hidden `
        -PassThru `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr

try {
    $health = $null
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            $health = Invoke-RestMethod "http://localhost:$port/health" -TimeoutSec 1
            break
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    if ($null -eq $health) {
        throw "Application did not become ready."
    }

    $dashboard = Invoke-WebRequest "http://localhost:$port/" -UseBasicParsing -TimeoutSec 5
    $dashboardJs = Invoke-WebRequest "http://localhost:$port/assets/dashboard.js" -UseBasicParsing -TimeoutSec 5
    $consoleData = Invoke-RestMethod "http://localhost:$port/api/console-data" -TimeoutSec 5
    $response = Invoke-RestMethod `
        "http://localhost:$port/recommend?userId=123&scene=mall&limit=5" `
        -TimeoutSec 5
    $metrics = Invoke-RestMethod "http://localhost:$port/metrics" -TimeoutSec 5

    if ($health.status -ne "UP") {
        throw "Expected health status UP, got '$($health.status)'."
    }
    if ($response.items.Count -ne 5) {
        throw "Expected 5 items, got '$($response.items.Count)'."
    }
    if ($dashboard.Content -notmatch 'id="recommendForm"') {
        throw "Dashboard HTML did not contain the expected title."
    }
    if ($dashboardJs.Content -notmatch "runRecommendation") {
        throw "Dashboard JavaScript did not contain the request flow."
    }
    if ($consoleData.userCount -lt 5 -or $consoleData.catalogCount -ne 100) {
        throw "Expected at least 5 console users and 100 catalog candidates."
    }

    [pscustomobject]@{
        health = $health.status
        dashboard = "OK"
        returnedItems = $response.items.Count
        metricGroups = $metrics.PSObject.Properties.Name.Count
        consoleUsers = $consoleData.userCount
        catalogCandidates = $consoleData.catalogCount
    }
} finally {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
    }
    if ($null -ne $process) {
        Wait-Process -Id $process.Id -ErrorAction SilentlyContinue
    }
}
