param(
    [switch]$KeepCluster
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$kindVersion = "v0.31.0"
$kindImage = "kindest/node:v1.31.2@sha256:18fbefc20a7113353c7b75b5c869d7145a6abd6269154825872dc59c1329912e"
$clusterName = "mini-reco-v17"
$namespace = "mini-reco"
$baseUrl = "http://localhost:18094"
$kind = Join-Path $projectRoot "target/tools/kind.exe"
$created = $false
$previousRuntimeImage = $env:MINI_RECO_RUNTIME_IMAGE

function Invoke-Native {
    param([string]$File, [string[]]$Arguments)
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$File $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

function Test-Image {
    param([string]$Image)
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & docker image inspect $Image 1>$null 2>$null
        return $LASTEXITCODE -eq 0
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Wait-Recommendation {
    param(
        [int]$ExpectedCount,
        [string]$ExpectedLiveStatus,
        [int]$MaxAttempts = 35
    )
    for ($i = 1; $i -le $MaxAttempts; $i++) {
        try {
            $response = Invoke-RestMethod "$baseUrl/recommend?userId=10001&scene=mall&limit=25" -TimeoutSec 8
            if ($response.debug.recallItemCount -eq $ExpectedCount `
                    -and $response.debug.resilience.live.status -eq $ExpectedLiveStatus) {
                return $response
            }
        }
        catch {
            # Pod startup, EndpointSlice convergence and circuit recovery are eventually consistent.
        }
        try { $null = Invoke-RestMethod "$baseUrl/resilience?reset=true" -TimeoutSec 3 } catch {}
        Start-Sleep -Milliseconds 700
    }
    throw "recommendation did not settle at count=$ExpectedCount, live=$ExpectedLiveStatus"
}

Push-Location $projectRoot
try {
    Write-Host "[1/11] Running all unit and integration tests..."
    Invoke-Native "mvn" @("package")

    Write-Host "[2/11] Installing the pinned kind CLI when needed..."
    if (-not (Test-Path $kind)) {
        New-Item -ItemType Directory -Force (Split-Path -Parent $kind) | Out-Null
        Invoke-WebRequest "https://github.com/kubernetes-sigs/kind/releases/download/$kindVersion/kind-windows-amd64" -OutFile $kind
    }
    Invoke-Native $kind @("version")
    $rendered = & kubectl kustomize deploy/k8s/overlays/local
    if ($LASTEXITCODE -ne 0 -or ($rendered -join "`n") -notmatch "kind: Deployment") {
        throw "Kustomize render validation failed"
    }

    Write-Host "[3/11] Building the V17 non-root application image..."
    if (Test-Image "eclipse-temurin:17-jre-jammy") {
        $env:MINI_RECO_RUNTIME_IMAGE = "eclipse-temurin:17-jre-jammy"
    }
    elseif (Test-Image "docker-ai:latest") {
        $env:MINI_RECO_RUNTIME_IMAGE = "docker-ai:latest"
    }
    else {
        $env:MINI_RECO_RUNTIME_IMAGE = "eclipse-temurin:17-jre-jammy"
    }
    Invoke-Native "docker" @("build", "--pull=false", "--build-arg", "RUNTIME_IMAGE=$env:MINI_RECO_RUNTIME_IMAGE", "-t", "mini-reco-access-layer:v17", ".")

    Write-Host "[4/11] Creating a disposable Kubernetes cluster..."
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $kind delete cluster --name $clusterName 1>$null 2>$null
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    Invoke-Native $kind @("create", "cluster", "--config", "deploy/k8s/kind-config.yaml", "--image", $kindImage, "--wait", "180s")
    $created = $true
    Invoke-Native $kind @("load", "docker-image", "mini-reco-access-layer:v17", "--name", $clusterName)

    Write-Host "[5/11] Applying Kustomize resources and waiting for five Deployments..."
    Invoke-Native "kubectl" @("apply", "-k", "deploy/k8s/overlays/local")
    foreach ($deployment in @("trace-collector", "goods", "live", "ad", "gateway")) {
        Invoke-Native "kubectl" @("-n", $namespace, "rollout", "status", "deployment/$deployment", "--timeout=180s")
    }

    Write-Host "[6/11] Checking probes, Services, resources and secure container settings..."
    $deployments = kubectl -n $namespace get deployments -o json | ConvertFrom-Json
    $deploymentItems = @($deployments.items)
    $notReady = @($deploymentItems | Where-Object { [int]$_.status.readyReplicas -lt [int]$_.spec.replicas })
    if ($deploymentItems.Count -ne 5 -or $notReady.Count -ne 0) {
        throw "not all five Deployments have one ready Pod"
    }
    $gateway = $deploymentItems | Where-Object { $_.metadata.name -eq "gateway" }
    $container = $gateway.spec.template.spec.containers[0]
    if (-not $container.startupProbe -or -not $container.readinessProbe -or -not $container.livenessProbe `
            -or -not $gateway.spec.template.spec.securityContext.runAsNonRoot `
            -or -not $container.securityContext.readOnlyRootFilesystem `
            -or $container.resources.requests.cpu -ne "100m") {
        throw "gateway probes, security context or resource requests are incomplete"
    }
    $endpoints = kubectl -n $namespace get endpointslice -o json | ConvertFrom-Json
    foreach ($service in @("gateway", "goods", "live", "ad", "trace-collector")) {
        $slice = @($endpoints.items | Where-Object { $_.metadata.labels.'kubernetes.io/service-name' -eq $service })
        if ($slice.Count -eq 0 -or @($slice.endpoints | Where-Object { $_.conditions.ready }).Count -eq 0) {
            throw "Service $service has no ready EndpointSlice"
        }
    }

    Write-Host "[7/11] Verifying healthy fan-out through the NodePort (25 items)..."
    $healthy = Wait-Recommendation 25 "SUCCESS"

    Write-Host "[8/11] Scaling live to zero and verifying partial-result fallback (17 items)..."
    Invoke-Native "kubectl" @("-n", $namespace, "scale", "deployment/live", "--replicas=0")
    Invoke-Native "kubectl" @("-n", $namespace, "rollout", "status", "deployment/live", "--timeout=90s")
    $fallback = Wait-Recommendation 17 "FALLBACK"

    Write-Host "[9/11] Restoring live and verifying automatic recovery (25 items)..."
    Invoke-Native "kubectl" @("-n", $namespace, "scale", "deployment/live", "--replicas=1")
    Invoke-Native "kubectl" @("-n", $namespace, "rollout", "status", "deployment/live", "--timeout=180s")
    $recovered = Wait-Recommendation 25 "SUCCESS" 50

    Write-Host "[10/11] Performing a real rolling restart of the gateway..."
    Invoke-Native "kubectl" @("-n", $namespace, "rollout", "restart", "deployment/gateway")
    Invoke-Native "kubectl" @("-n", $namespace, "rollout", "status", "deployment/gateway", "--timeout=180s")
    $afterRollout = Wait-Recommendation 25 "SUCCESS" 50

    Write-Host "[11/11] Verifying HPA, PDB and configuration objects..."
    $hpa = kubectl -n $namespace get hpa gateway -o json | ConvertFrom-Json
    $pdb = kubectl -n $namespace get pdb gateway -o json | ConvertFrom-Json
    $config = kubectl -n $namespace get configmap mini-reco-config -o json | ConvertFrom-Json
    if ($hpa.spec.minReplicas -ne 1 -or $hpa.spec.maxReplicas -ne 3 -or $pdb.spec.minAvailable -ne 1 `
            -or $config.data.RECALL_TRANSPORT -ne "grpc") {
        throw "HPA, PDB or ConfigMap assertion failed"
    }

    Write-Host "V17 KUBERNETES ACCEPTANCE PASSED"
    Write-Host "healthy=$($healthy.debug.recallItemCount), live-down=$($fallback.debug.recallItemCount), recovered=$($recovered.debug.recallItemCount), rollout=$($afterRollout.debug.recallItemCount)"
    Write-Host "deployments=$($deploymentItems.Count), hpa=$($hpa.spec.minReplicas)-$($hpa.spec.maxReplicas), pdbMinAvailable=$($pdb.spec.minAvailable)"
    if ($KeepCluster) {
        Write-Host "Cluster retained. Run: kubectl -n mini-reco get all"
    }
}
finally {
    if ($created -and -not $KeepCluster) {
        Write-Host "Cleaning up disposable kind cluster..."
        $previousErrorActionPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            & $kind delete cluster --name $clusterName
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
    }
    $env:MINI_RECO_RUNTIME_IMAGE = $previousRuntimeImage
    Pop-Location
}
