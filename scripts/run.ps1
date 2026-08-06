$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if (-not (Test-NetConnection -ComputerName "localhost" -Port 3307 -InformationLevel Quiet)) {
        throw "MySQL is not reachable on localhost:3307. Run 'docker compose up -d db' first."
    }
    mvn -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    java -jar target/mini-reco-access-layer-0.1.0-SNAPSHOT-all.jar
} finally {
    Pop-Location
}
