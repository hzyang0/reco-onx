param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 18091
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot "target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar"
$responsePath = Join-Path $projectRoot "target/recommend-response.pb"
$stdoutPath = Join-Path $projectRoot "target/protobuf-demo.out.log"
$stderrPath = Join-Path $projectRoot "target/protobuf-demo.err.log"
$baseUrl = "http://localhost:$Port"
$process = $null

Push-Location $projectRoot
try {
    Write-Host "[1/5] Running tests and packaging the executable JAR..."
    & mvn package
    if ($LASTEXITCODE -ne 0) {
        throw "mvn package failed with exit code $LASTEXITCODE"
    }

    Remove-Item $responsePath, $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
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

    Write-Host "[3/5] Requesting the normal JSON endpoint..."
    $jsonResponse = Invoke-RestMethod "$baseUrl/recommend?userId=123&scene=mall&limit=10"
    Write-Host "JSON item count:" $jsonResponse.items.Count

    Write-Host "[4/5] Requesting and saving the binary Protobuf endpoint..."
    Invoke-WebRequest `
        "$baseUrl/recommend-pb?userId=123&scene=mall&limit=10" `
        -OutFile $responsePath `
        -UseBasicParsing
    if ((Get-Item $responsePath).Length -le 0) {
        throw "protobuf response file is empty"
    }

    Write-Host "[5/5] Decoding the binary response with generated Java classes..."
    & java -cp $jarPath com.interview.minireco.proto.ProtoResponseDecoder $responsePath
    if ($LASTEXITCODE -ne 0) {
        throw "protobuf decode failed with exit code $LASTEXITCODE"
    }
    Write-Host "binary response:" $responsePath
}
finally {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
        Wait-Process -Id $process.Id -ErrorAction SilentlyContinue
    }
    Pop-Location
}
