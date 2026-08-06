$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$testUserId = 99001
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
    $goodsCount = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT count(*) FROM catalog_items WHERE source='goods'" mini_reco).Trim()
    $distinctGoodsTitleCount = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT count(DISTINCT title) FROM catalog_items WHERE source='goods'" mini_reco).Trim()
    $nonUniqueSourceCount = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT COUNT(*) FROM (SELECT source FROM catalog_items GROUP BY source HAVING COUNT(DISTINCT title) <> 100) title_counts" mini_reco).Trim()
    $crossSourceTitleOverlapCount = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT COUNT(*) FROM catalog_items a JOIN catalog_items b ON a.title=b.title AND a.source < b.source" mini_reco).Trim()
    $derivedPrefixCount = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT COUNT(*) FROM catalog_items WHERE title LIKE '直播精选｜%' OR title LIKE '品牌活动｜%'" mini_reco).Trim()
    $imbalancedSourceCount = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT COUNT(*) FROM (SELECT source FROM catalog_items GROUP BY source HAVING COUNT(*) <> 100) source_counts" mini_reco).Trim()
    $imbalancedCategorySourceCount = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT COUNT(*) FROM (SELECT source,category FROM catalog_items GROUP BY source,category HAVING COUNT(*) <> 20) bucket_counts" mini_reco).Trim()
    $variantSuffixCount = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT count(*) FROM catalog_items WHERE source='goods' AND (title LIKE '%轻享款%' OR title LIKE '%进阶款%' OR title LIKE '%旗舰款%')" mini_reco).Trim()
    $innodbTableCount = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT count(*) FROM information_schema.tables WHERE table_schema='mini_reco' AND engine='InnoDB'" mini_reco).Trim()
    $businessIndexCount = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT count(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='mini_reco' AND index_name IN ('idx_user_events_user_time','idx_catalog_items_source_score')" mini_reco).Trim()
    $firstTitleUtf8Hex = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT HEX(title) FROM catalog_items WHERE item_id=11001" mini_reco).Trim()
    if ([int]$profileCount -lt 5 -or [int]$catalogCount -ne 300) {
        throw "Expected at least 5 user profiles and 300 catalog items, got user_profiles=$profileCount catalog_items=$catalogCount."
    }
    if ([int]$goodsCount -ne 100 -or [int]$distinctGoodsTitleCount -ne 100 -or [int]$variantSuffixCount -ne 0) {
        throw "Expected 100 unique one-style goods and no generated variant suffixes."
    }
    if ([int]$nonUniqueSourceCount -ne 0 -or [int]$crossSourceTitleOverlapCount -ne 0 -or [int]$derivedPrefixCount -ne 0) {
        throw "Expected 100 unique titles per source, no cross-source title overlap, and no derived live/ad prefixes."
    }
    if ([int]$imbalancedSourceCount -ne 0 -or [int]$imbalancedCategorySourceCount -ne 0) {
        throw "Expected goods/live/ad to have 100 candidates each and every source-category bucket to have 20."
    }
    if ([int]$innodbTableCount -ne 5 -or [int]$businessIndexCount -ne 2) {
        throw "Expected 5 InnoDB tables and 2 business indexes, got tables=$innodbTableCount indexes=$businessIndexCount."
    }

    $dashboard = Invoke-WebRequest "http://localhost:$port/" -UseBasicParsing -TimeoutSec 5
    $consoleData = Invoke-RestMethod "http://localhost:$port/api/console-data" -TimeoutSec 5
    $response = Invoke-RestMethod "http://localhost:$port/recommend?userId=123&scene=mall&limit=5" -TimeoutSec 5
    $mallResponse = Invoke-RestMethod "http://localhost:$port/recommend?userId=123&scene=mall&limit=10" -TimeoutSec 5
    $videoResponse = Invoke-RestMethod "http://localhost:$port/recommend?userId=456&scene=video_feed&limit=10" -TimeoutSec 5
    $buyerHomeResponse = Invoke-RestMethod "http://localhost:$port/recommend?userId=2024&scene=buy_first&limit=10" -TimeoutSec 5
    $coldStartResponse = Invoke-RestMethod "http://localhost:$port/recommend?userId=1000&scene=mall&limit=5" -TimeoutSec 5
    $metrics = Invoke-RestMethod "http://localhost:$port/metrics" -TimeoutSec 5

    if ($response.items.Count -ne 5) {
        throw "Expected 5 recommended items, got $($response.items.Count)."
    }
    $sourceRecallCounts = $response.debug.recallFanout.itemCountBySource
    if ($sourceRecallCounts.goods -ne 20 -or $sourceRecallCounts.live -ne 20 -or $sourceRecallCounts.ad -ne 20) {
        throw "Expected balanced 20/20/20 recall results, got goods=$($sourceRecallCounts.goods) live=$($sourceRecallCounts.live) ad=$($sourceRecallCounts.ad)."
    }
    $mallSources = @($mallResponse.items | ForEach-Object { $_.source }) -join ","
    $videoSources = @($videoResponse.items | ForEach-Object { $_.source }) -join ","
    $buyerHomeSources = @($buyerHomeResponse.items | ForEach-Object { $_.source }) -join ","
    if ($mallSources -ne "goods,goods,goods,ad,goods,goods,goods,goods,ad,goods") {
        throw "Mall scene returned an unexpected source layout: $mallSources"
    }
    if ($videoSources -ne "live,live,live,ad,live,live,live,live,ad,live") {
        throw "Video-feed scene returned an unexpected source layout: $videoSources"
    }
    if ($buyerHomeSources -ne "goods,live,goods,ad,live,goods,live,goods,ad,live") {
        throw "Buyer-home scene returned an unexpected source layout: $buyerHomeSources"
    }
    if (-not $coldStartResponse.debug.rankingPolicy.coldStart) {
        throw "New user did not activate the cold-start ranking policy."
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
    if ($consoleData.userCount -lt 5 -or $consoleData.catalogCount -ne 300) {
        throw "Console data endpoint returned unexpected counts."
    }
    $personaNames = @($consoleData.users | ForEach-Object { $_.personaName } | Select-Object -Unique)
    if ($personaNames.Count -lt 5) {
        throw "Expected at least five distinct user personas, got $($personaNames.Count)."
    }

    docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "DELETE FROM user_events WHERE user_id=$testUserId; DELETE FROM experiment_assignments WHERE user_id=$testUserId; DELETE FROM user_profiles WHERE user_id=$testUserId" mini_reco
    $createdUser = Invoke-RestMethod `
            "http://localhost:$port/api/users" `
            -Method Post `
            -ContentType "application/x-www-form-urlencoded" `
            -Body @{
                userId = $testUserId
                age = 28
                personaName = "Integration Runner"
                personaSummary = "Sports profile created by integration test"
                province = "Zhejiang"
                city = "Hangzhou"
                category = "sports"
                behaviorLevel = "high_intent"
                scene = "mall"
                rankExperiment = "MALL_BOOST"
            } `
            -TimeoutSec 5
    $createdRecommendation = Invoke-RestMethod `
            "http://localhost:$port/recommend?userId=$testUserId&scene=mall&limit=5" `
            -TimeoutSec 5
    $createdEventCount = (docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "SELECT COUNT(*) FROM user_events WHERE user_id=$testUserId" mini_reco).Trim()
    if (-not $createdUser.created -or $createdRecommendation.items[0].category -ne "sports" -or [int]$createdEventCount -ne 4) {
        throw "Created profile did not persist or affect recommendation as expected."
    }

    [pscustomobject]@{
        health = $health.status
        profileRows = [int]$profileCount
        catalogRows = [int]$catalogCount
        uniqueGoods = [int]$distinctGoodsTitleCount
        uniqueTitlesPerSource = 100
        crossSourceTitleOverlap = [int]$crossSourceTitleOverlapCount
        derivedSourceTitles = [int]$derivedPrefixCount
        candidatesPerSource = 100
        recallItemsPerSource = "20/20/20"
        mallLayout = $mallSources
        videoLayout = $videoSources
        buyerHomeLayout = $buyerHomeSources
        coldStartPolicy = "OK"
        generatedVariants = [int]$variantSuffixCount
        innodbTables = [int]$innodbTableCount
        businessIndexes = [int]$businessIndexCount
        returnedItems = $response.items.Count
        firstItemId = $response.items[0].itemId
        utf8Title = "OK"
        metricGroups = $metrics.PSObject.Properties.Name.Count
        console = "OK"
        consoleUsers = $consoleData.userCount
        catalogCandidates = $consoleData.catalogCount
        profileCreation = "OK"
        createdBehaviorEvents = [int]$createdEventCount
    }
} finally {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
    }
    if ($null -ne $process) {
        Wait-Process -Id $process.Id -ErrorAction SilentlyContinue
    }
    docker compose exec -T -e MYSQL_PWD=mini_reco db mysql -umini_reco -Nse "DELETE FROM user_events WHERE user_id=$testUserId; DELETE FROM experiment_assignments WHERE user_id=$testUserId; DELETE FROM user_profiles WHERE user_id=$testUserId" mini_reco 2>$null
    Pop-Location
}
