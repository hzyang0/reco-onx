param(
    [ValidateRange(1024, 65535)]
    [int]$GatewayPort = 18092,
    [ValidateRange(1024, 65535)]
    [int]$GoodsPort = 19001,
    [ValidateRange(1024, 65535)]
    [int]$LivePort = 19002,
    [ValidateRange(1024, 65535)]
    [int]$AdPort = 19003
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot "target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar"
$baseUrl = "http://localhost:$GatewayPort"
$processes = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()

function Wait-TcpPort {
    param([int]$Port, [string]$Name)
    for ($i = 0; $i -lt 60; $i++) {
        $client = [System.Net.Sockets.TcpClient]::new()
        try {
            $task = $client.ConnectAsync("127.0.0.1", $Port)
            if ($task.Wait(200) -and $client.Connected) {
                return
            }
        }
        catch {
            # The process may still be starting.
        }
        finally {
            $client.Dispose()
        }
        Start-Sleep -Milliseconds 150
    }
    throw "$Name did not listen on port $Port"
}

function Start-Downstream {
    param([string]$Source, [int]$Port)
    $stdout = Join-Path $projectRoot "target/grpc-$Source.out.log"
    $stderr = Join-Path $projectRoot "target/grpc-$Source.err.log"
    Remove-Item $stdout, $stderr -Force -ErrorAction SilentlyContinue
    $process = Start-Process -FilePath "java" `
        -ArgumentList "-cp", $jarPath, "com.interview.minireco.grpc.server.DownstreamGrpcApplication", $Source, $Port `
        -WorkingDirectory $projectRoot `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru
    $processes.Add($process)
    return $process
}

function Wait-HealthyGrpcResult {
    for ($i = 0; $i -lt 6; $i++) {
        $response = Invoke-RestMethod "$baseUrl/recommend?userId=123&scene=mall&limit=10"
        if ($response.debug.recallItemCount -eq 25 `
                -and $response.debug.resilience.goods.status -eq "SUCCESS" `
                -and $response.debug.resilience.live.status -eq "SUCCESS" `
                -and $response.debug.resilience.ad.status -eq "SUCCESS") {
            return $response
        }
        $null = Invoke-RestMethod "$baseUrl/resilience?reset=true" -Method Post
        Start-Sleep -Milliseconds 400
    }
    throw "gRPC recall did not reach a healthy 25-item result"
}

Push-Location $projectRoot
try {
    Write-Host "[1/7] Running tests and packaging the fat JAR..."
    & mvn package
    if ($LASTEXITCODE -ne 0) {
        throw "mvn package failed with exit code $LASTEXITCODE"
    }

    Write-Host "[2/7] Starting goods, live and ad as three gRPC processes..."
    $goodsProcess = Start-Downstream "goods" $GoodsPort
    $liveProcess = Start-Downstream "live" $LivePort
    $adProcess = Start-Downstream "ad" $AdPort
    Wait-TcpPort $GoodsPort "goods gRPC"
    Wait-TcpPort $LivePort "live gRPC"
    Wait-TcpPort $AdPort "ad gRPC"

    Write-Host "[3/7] Starting the access-layer process in grpc transport mode..."
    $gatewayOut = Join-Path $projectRoot "target/grpc-gateway.out.log"
    $gatewayErr = Join-Path $projectRoot "target/grpc-gateway.err.log"
    Remove-Item $gatewayOut, $gatewayErr -Force -ErrorAction SilentlyContinue
    $gatewayArgs = @(
        "-Dreco.recall.transport=grpc",
        "-Dreco.grpc.goods.target=localhost:$GoodsPort",
        "-Dreco.grpc.live.target=localhost:$LivePort",
        "-Dreco.grpc.ad.target=localhost:$AdPort",
        "-Dreco.grpc.deadline.ms=70",
        "-jar", $jarPath, $GatewayPort
    )
    $gatewayProcess = Start-Process -FilePath "java" `
        -ArgumentList $gatewayArgs `
        -WorkingDirectory $projectRoot `
        -RedirectStandardOutput $gatewayOut `
        -RedirectStandardError $gatewayErr `
        -WindowStyle Hidden `
        -PassThru
    $processes.Add($gatewayProcess)
    Wait-TcpPort $GatewayPort "access layer"
    $health = Invoke-RestMethod "$baseUrl/health"
    if ($health.recallTransport.mode -ne "grpc") {
        throw "gateway did not start in grpc mode"
    }

    Write-Host "[4/7] Calling all three downstream processes concurrently..."
    $healthy = Wait-HealthyGrpcResult
    $metrics = Invoke-RestMethod "$baseUrl/metrics"
    $grpcSuccess = @($metrics.metrics | Where-Object {
        $_.name -eq "grpc.client.call" -and $_.tags.status -eq "success"
    })
    if ($grpcSuccess.Count -lt 3) {
        throw "expected successful gRPC metrics for goods, live and ad"
    }
    Start-Sleep -Milliseconds 100
    $goodsLog = Get-Content (Join-Path $projectRoot "target/grpc-goods.out.log") -Raw
    if ($goodsLog -notlike "*$($healthy.requestId)*") {
        throw "requestId was not propagated to the goods process"
    }

    Write-Host "[5/7] Killing the live process and checking partial fallback..."
    Stop-Process -Id $liveProcess.Id -Force
    Wait-Process -Id $liveProcess.Id -ErrorAction SilentlyContinue
    $failed = Invoke-RestMethod "$baseUrl/recommend?userId=124&scene=mall&limit=10"
    if ($failed.debug.resilience.live.status -ne "FALLBACK") {
        throw "live process failure should trigger fallback"
    }
    if ($failed.debug.recallItemCount -ne 17) {
        throw "goods + ad partial result should contain 17 recalled items"
    }

    Write-Host "[6/7] Restarting live, resetting the circuit and verifying recovery..."
    $liveProcess = Start-Downstream "live" $LivePort
    Wait-TcpPort $LivePort "restarted live gRPC"
    $null = Invoke-RestMethod "$baseUrl/resilience?reset=true" -Method Post
    Start-Sleep -Milliseconds 200
    $recovered = Wait-HealthyGrpcResult

    Write-Host "[7/7] gRPC multi-process verification completed."
    @(
        [pscustomobject]@{
            Scenario = "healthy"
            RecallItems = $healthy.debug.recallItemCount
            ReturnedItems = $healthy.items.Count
            LiveStatus = $healthy.debug.resilience.live.status
            Transport = $healthy.debug.recallTransport
        },
        [pscustomobject]@{
            Scenario = "live_process_down"
            RecallItems = $failed.debug.recallItemCount
            ReturnedItems = $failed.items.Count
            LiveStatus = $failed.debug.resilience.live.status
            Transport = $failed.debug.recallTransport
        },
        [pscustomobject]@{
            Scenario = "live_recovered"
            RecallItems = $recovered.debug.recallItemCount
            ReturnedItems = $recovered.items.Count
            LiveStatus = $recovered.debug.resilience.live.status
            Transport = $recovered.debug.recallTransport
        }
    ) | Format-Table -AutoSize
    Write-Host "gateway log:" $gatewayOut
}
finally {
    foreach ($process in $processes) {
        if ($null -ne $process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            Wait-Process -Id $process.Id -ErrorAction SilentlyContinue
        }
    }
    Pop-Location
}
