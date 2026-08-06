$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    mvn --batch-mode --no-transfer-progress -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    docker compose up -d --wait db
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    $portProbe = [System.Net.Sockets.TcpListener]::new(
            [System.Net.IPAddress]::Loopback,
            0
    )
    $portProbe.Start()
    $port = ([System.Net.IPEndPoint]$portProbe.LocalEndpoint).Port
    $portProbe.Stop()
    $stdout = Join-Path $root "target/database-integration.out.log"
    $stderr = Join-Path $root "target/database-integration.err.log"
    Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
    $process = Start-Process `
            -FilePath "java" `
            -ArgumentList @("-jar", "target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar", "$port") `
            -WorkingDirectory $root `
            -WindowStyle Hidden `
            -PassThru `
            -RedirectStandardOutput $stdout `
            -RedirectStandardError $stderr

    $health = $null
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        try {
            $health = Invoke-RestMethod "http://localhost:$port/health" -TimeoutSec 2
            break
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    if ($null -eq $health -or $health.status -ne "UP") {
        $startupError = if (Test-Path -LiteralPath $stderr) { Get-Content -Raw $stderr } else { "" }
        throw "Application did not become healthy. $startupError"
    }

    $profileCount = (docker compose exec -T db psql -U mini_reco -d mini_reco -tAc "SELECT count(*) FROM user_profiles").Trim()
    $catalogCount = (docker compose exec -T db psql -U mini_reco -d mini_reco -tAc "SELECT count(*) FROM catalog_items").Trim()
    if ([int]$profileCount -lt 1 -or [int]$catalogCount -lt 1) {
        throw "Expected seeded database records, got user_profiles=$profileCount catalog_items=$catalogCount."
    }

    $dashboard = Invoke-WebRequest "http://localhost:$port/" -UseBasicParsing -TimeoutSec 5
    $response = Invoke-RestMethod "http://localhost:$port/recommend?userId=123&scene=mall&limit=5" -TimeoutSec 5
    $metrics = Invoke-RestMethod "http://localhost:$port/metrics" -TimeoutSec 5

    if ($response.items.Count -ne 5) {
        throw "Expected 5 recommended items, got $($response.items.Count)."
    }
    if ($dashboard.Content -notmatch 'id="recommendForm"') {
        throw "Dashboard HTML did not contain the recommendation form."
    }

    [pscustomobject]@{
        health = $health.status
        profileRows = [int]$profileCount
        catalogRows = [int]$catalogCount
        returnedItems = $response.items.Count
        metricGroups = $metrics.PSObject.Properties.Name.Count
        console = "OK"
    }
} finally {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
    }
    if ($null -ne $process) {
        Wait-Process -Id $process.Id -ErrorAction SilentlyContinue
    }
    Pop-Location
}
