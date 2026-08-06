$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root "target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar"
$stdout = Join-Path $root "target/app.out.log"
$stderr = Join-Path $root "target/app.err.log"

if (-not (Test-Path -LiteralPath $jar)) {
    throw "Missing application JAR. Run 'mvn -DskipTests package' first."
}
if (-not (Test-NetConnection -ComputerName "localhost" -Port 5432 -InformationLevel Quiet)) {
    throw "PostgreSQL is not reachable on localhost:5432. Run 'docker compose up -d db' first."
}
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw "Port 8080 is already in use."
}

Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
$process = Start-Process `
        -FilePath "java" `
        -ArgumentList @("-jar", $jar, "8080") `
        -WorkingDirectory $root `
        -WindowStyle Hidden `
        -PassThru `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr

$health = $null
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    try {
        $health = Invoke-RestMethod "http://localhost:8080/health" -TimeoutSec 1
        break
    } catch {
        Start-Sleep -Milliseconds 300
    }
}

if ($null -eq $health -or $health.status -ne "UP") {
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
    }
    $startupError = if (Test-Path -LiteralPath $stderr) { Get-Content -Raw $stderr } else { "" }
    throw "Application did not become healthy. $startupError"
}

[pscustomobject]@{
    pid = $process.Id
    health = $health.status
    console = "http://localhost:8080/"
    log = $stdout
}
