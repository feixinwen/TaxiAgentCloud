[CmdletBinding()]
param(
    [string]$Registry,
    [string]$Tag,
    [switch]$Push
)

function Get-TaxiAgentImageDefinitions {
    $version = '0.0.1-SNAPSHOT'
    @(
        [PSCustomObject]@{ Name = 'taxiagent-gateway'; Type = 'java'; JarPath = "gateway/taxiagent-gateway/target/taxiagent-gateway-$version.jar" }
        [PSCustomObject]@{ Name = 'taxiagent-user-service'; Type = 'java'; JarPath = "services/taxiagent-user-service/target/taxiagent-user-service-$version.jar" }
        [PSCustomObject]@{ Name = 'taxiagent-auth-service'; Type = 'java'; JarPath = "services/taxiagent-auth-service/target/taxiagent-auth-service-$version.jar" }
        [PSCustomObject]@{ Name = 'taxiagent-order-service'; Type = 'java'; JarPath = "services/taxiagent-order-service/target/taxiagent-order-service-$version.jar" }
        [PSCustomObject]@{ Name = 'taxiagent-ticket-service'; Type = 'java'; JarPath = "services/taxiagent-ticket-service/target/taxiagent-ticket-service-$version.jar" }
        [PSCustomObject]@{ Name = 'taxiagent-rag-service'; Type = 'java'; JarPath = "services/taxiagent-rag-service/target/taxiagent-rag-service-$version.jar" }
        [PSCustomObject]@{ Name = 'taxiagent-agent-service'; Type = 'java'; JarPath = "services/taxiagent-agent-service/target/taxiagent-agent-service-$version.jar" }
        [PSCustomObject]@{ Name = 'taxiagent-client-service'; Type = 'frontend'; JarPath = $null }
    )
}

function Assert-ImageTag {
    param([string]$Tag)
    if ($Tag -notmatch '^[0-9a-f]{40}$') {
        throw "Image tag must be a 40-character lowercase hex commit SHA, got '$Tag'"
    }
}

if ($MyInvocation.InvocationName -eq '.') {
    return
}

if (-not $Registry) {
    throw 'Registry is required: -Registry <host>'
}
if (-not $Tag) {
    throw 'Tag is required: -Tag <40-char commit sha>'
}
Assert-ImageTag $Tag

$repoRoot = Split-Path -Parent $PSScriptRoot
$images = Get-TaxiAgentImageDefinitions

foreach ($image in $images) {
    if ($image.Type -eq 'frontend') {
        continue
    }
    $jar = Join-Path $repoRoot ($image.JarPath -replace '/', [IO.Path]::DirectorySeparatorChar)
    if (-not (Test-Path $jar)) {
        throw "Jar not found: $jar - run 'mvnw.cmd -DskipTests package' first"
    }
}

foreach ($image in $images) {
    $tag = "$Registry/taxiagent/$($image.Name):$Tag"
    if ($image.Type -eq 'frontend') {
        $file = Join-Path $repoRoot 'services/taxiagent-client-service/Dockerfile'
        $context = Join-Path $repoRoot 'services/taxiagent-client-service'
        & docker build --file $file --tag $tag $context
    }
    else {
        $file = Join-Path $repoRoot 'deploy/docker/java-service.Dockerfile'
        & docker build --file $file --build-arg "JAR_FILE=$($image.JarPath)" --tag $tag $repoRoot
    }
    if ($LASTEXITCODE -ne 0) {
        throw "docker build failed for $($image.Name)"
    }
    Write-Host "built $tag"

    if ($Push) {
        & docker push $tag
        if ($LASTEXITCODE -ne 0) {
            throw "docker push failed for $tag"
        }
    }
}
