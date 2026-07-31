$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    mvn -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    java -jar target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar
} finally {
    Pop-Location
}
