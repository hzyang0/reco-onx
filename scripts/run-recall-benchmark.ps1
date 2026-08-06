param(
    [int]$Requests = 200,
    [int]$WarmupRequests = 20
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root "target/mini-reco-access-layer-0.1.0-SNAPSHOT-all.jar"
$previousParallelism = $env:RECALL_FANOUT_PARALLELISM

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    $listener.Stop()
    return $port
}

function Percentile([double[]]$values, [double]$percentile) {
    $sorted = @($values | Sort-Object)
    $index = [Math]::Ceiling($percentile * $sorted.Count) - 1
    return [Math]::Round($sorted[[Math]::Max(0, $index)], 2)
}

function Measure-Mode([string]$name, [int]$parallelism) {
    $port = Get-FreePort
    $env:RECALL_FANOUT_PARALLELISM = [string]$parallelism
    $stdout = Join-Path $root "target/benchmark-$name.out.log"
    $stderr = Join-Path $root "target/benchmark-$name.err.log"
    Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
    $process = Start-Process -FilePath "java" `
            -ArgumentList @("-jar", $jar, "$port") `
            -WorkingDirectory $root `
            -WindowStyle Hidden `
            -PassThru `
            -RedirectStandardOutput $stdout `
            -RedirectStandardError $stderr
    try {
        $ready = $false
        for ($attempt = 0; $attempt -lt 40; $attempt++) {
            try {
                $health = Invoke-RestMethod "http://localhost:$port/health" -TimeoutSec 2
                if ($health.status -eq "UP") { $ready = $true; break }
            } catch {
                Start-Sleep -Milliseconds 250
            }
        }
        if (-not $ready) { throw "Benchmark application did not become ready in $name mode." }

        for ($i = 0; $i -lt $WarmupRequests; $i++) {
            Invoke-RestMethod "http://localhost:$port/recommend?userId=123&scene=buy_first&limit=10" -TimeoutSec 3 | Out-Null
        }

        [double[]]$latencies = @()
        $total = [System.Diagnostics.Stopwatch]::StartNew()
        for ($i = 0; $i -lt $Requests; $i++) {
            $requestTimer = [System.Diagnostics.Stopwatch]::StartNew()
            Invoke-RestMethod "http://localhost:$port/recommend?userId=123&scene=buy_first&limit=10" -TimeoutSec 3 | Out-Null
            $requestTimer.Stop()
            $latencies += $requestTimer.Elapsed.TotalMilliseconds
        }
        $total.Stop()
        return [pscustomobject]@{
            mode = $name
            recallParallelism = $parallelism
            requests = $Requests
            averageMs = [Math]::Round(($latencies | Measure-Object -Average).Average, 2)
            p50Ms = Percentile $latencies 0.50
            p95Ms = Percentile $latencies 0.95
            maxMs = [Math]::Round(($latencies | Measure-Object -Maximum).Maximum, 2)
            throughputRps = [Math]::Round($Requests / $total.Elapsed.TotalSeconds, 2)
            errors = 0
        }
    } finally {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
        Wait-Process -Id $process.Id -ErrorAction SilentlyContinue
    }
}

Push-Location $root
try {
    if (-not (Test-Path -LiteralPath $jar)) {
        mvn --batch-mode --no-transfer-progress -DskipTests package
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    if (-not (Test-NetConnection -ComputerName localhost -Port 3307 -InformationLevel Quiet)) {
        throw "MySQL is not reachable on localhost:3307. Start it with 'docker compose up -d db'."
    }
    $serial = Measure-Mode "serial" 1
    $parallel = Measure-Mode "parallel" 3
    $improvement = if ($serial.p95Ms -eq 0) { 0 } else {
        [Math]::Round((($serial.p95Ms - $parallel.p95Ms) / $serial.p95Ms) * 100, 2)
    }
    [pscustomobject]@{
        serial = $serial
        parallel = $parallel
        p95ImprovementPercent = $improvement
        note = "Local MySQL benchmark; results vary by machine and background load."
    }
} finally {
    $env:RECALL_FANOUT_PARALLELISM = $previousParallelism
    Pop-Location
}
