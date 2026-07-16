param(
    [ValidateRange(1, 100)]
    [int]$Iterations = 5,
    [string]$OutputPath = "target/degradation-benchmark.csv"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot "target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar"
$previousLogLevel = $env:LOG_LEVEL

Push-Location $projectRoot
try {
    Write-Host "[1/3] Running unit tests..."
    & mvn test
    if ($LASTEXITCODE -ne 0) {
        throw "mvn test failed with exit code $LASTEXITCODE"
    }

    Write-Host "[2/3] Packaging application..."
    & mvn "-DskipTests" package
    if ($LASTEXITCODE -ne 0) {
        throw "mvn package failed with exit code $LASTEXITCODE"
    }

    Write-Host "[3/3] Running degradation benchmark..."
    $env:LOG_LEVEL = "ERROR"
    & java -cp $jarPath com.interview.minireco.benchmark.DegradationBenchmark $Iterations $OutputPath
    if ($LASTEXITCODE -ne 0) {
        throw "benchmark failed with exit code $LASTEXITCODE"
    }
}
finally {
    if ($null -eq $previousLogLevel) {
        Remove-Item Env:LOG_LEVEL -ErrorAction SilentlyContinue
    }
    else {
        $env:LOG_LEVEL = $previousLogLevel
    }
    Pop-Location
}
