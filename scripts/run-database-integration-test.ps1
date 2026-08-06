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
            -ArgumentList @("-jar", "target/mini-reco-access-layer-0.1.0-SNAPSHOT-all.jar", "$port") `
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

    $profileCount = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT count(*) FROM user_profiles" mini_reco).Trim()
    $catalogCount = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT count(*) FROM catalog_items" mini_reco).Trim()
    $innodbTableCount = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT count(*) FROM information_schema.tables WHERE table_schema='mini_reco' AND engine='InnoDB'" mini_reco).Trim()
    $businessIndexCount = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT count(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='mini_reco' AND index_name IN ('idx_user_events_user_time','idx_catalog_items_source_score')" mini_reco).Trim()
    $firstTitleUtf8Hex = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT HEX(title) FROM catalog_items WHERE item_id=11001" mini_reco).Trim()
    if ([int]$profileCount -ne 5 -or [int]$catalogCount -ne 100) {
        throw "Expected 5 user profiles and 100 catalog items, got user_profiles=$profileCount catalog_items=$catalogCount."
    }
    if ([int]$innodbTableCount -ne 5 -or [int]$businessIndexCount -ne 2) {
        throw "Expected 5 InnoDB tables and 2 business indexes, got tables=$innodbTableCount indexes=$businessIndexCount."
    }

    $dashboard = Invoke-WebRequest "http://localhost:$port/" -UseBasicParsing -TimeoutSec 5
    $consoleData = Invoke-RestMethod "http://localhost:$port/api/console-data" -TimeoutSec 5
    $response = Invoke-RestMethod "http://localhost:$port/recommend?userId=123&scene=mall&limit=5" -TimeoutSec 5
    $metrics = Invoke-RestMethod "http://localhost:$port/metrics" -TimeoutSec 5

    if ($response.items.Count -ne 5) {
        throw "Expected 5 recommended items, got $($response.items.Count)."
    }
    if ($response.items[0].itemId -ne 11001 -or $firstTitleUtf8Hex -ne "E58C97E6ACA7E694B6E7BAB3E7AEB1") {
        throw "Expected item 11001 and valid UTF-8 title bytes, got itemId=$($response.items[0].itemId) hex=$firstTitleUtf8Hex."
    }
    $invalidItems = @($response.items | Where-Object {
        $_.source -eq "fallback" -or $_.attrs.status -ne "ONLINE" -or [int]$_.attrs.stock -le 0
    })
    if ($invalidItems.Count -ne 0) {
        throw "Recommendation contained fallback, offline, or out-of-stock items."
    }
    if ($dashboard.Content -notmatch 'id="recommendForm"') {
        throw "Dashboard HTML did not contain the recommendation form."
    }
    if ($consoleData.userCount -ne 5 -or $consoleData.catalogCount -ne 100) {
        throw "Console data endpoint returned unexpected counts."
    }
    $personaNames = @($consoleData.users | ForEach-Object { $_.personaName } | Select-Object -Unique)
    if ($personaNames.Count -ne 5) {
        throw "Expected five distinct user personas, got $($personaNames.Count)."
    }

    [pscustomobject]@{
        health = $health.status
        profileRows = [int]$profileCount
        catalogRows = [int]$catalogCount
        innodbTables = [int]$innodbTableCount
        businessIndexes = [int]$businessIndexCount
        returnedItems = $response.items.Count
        firstItemId = $response.items[0].itemId
        utf8Title = "OK"
        metricGroups = $metrics.PSObject.Properties.Name.Count
        console = "OK"
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
    Pop-Location
}
