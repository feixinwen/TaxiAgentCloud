[CmdletBinding()]
param(
    [string]$Registry,
    [string]$Tag
)

. (Join-Path $PSScriptRoot 'build-images.ps1')

function Get-DockerImageInspect {
    param([string]$ImageTag)
    $json = & docker image inspect $ImageTag --format '{{json .}}' 2>$null
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    return ($json | ConvertFrom-Json)
}

function Invoke-VerifyImages {
    param(
        [Parameter(Mandatory)][string]$Registry,
        [Parameter(Mandatory)][string]$Tag
    )
    Assert-ImageTag $Tag
    foreach ($image in (Get-TaxiAgentImageDefinitions)) {
        $fullTag = "$Registry/taxiagent/$($image.Name):$Tag"
        $img = Get-DockerImageInspect $fullTag
        if ($null -eq $img) {
            throw "Image not found: $fullTag"
        }
        if ($img.RepoTags -notcontains $fullTag) {
            throw "Image '$fullTag' is not tagged with the requested SHA"
        }
        if ($img.Architecture -ne 'amd64') {
            throw "Image '$fullTag' architecture is '$($img.Architecture)', expected amd64"
        }
        $user = $img.Config.User
        if ([string]::IsNullOrWhiteSpace($user) -or $user -eq '0' -or $user -ieq 'root') {
            throw "Image '$fullTag' runs as '$user' (must be a non-root user)"
        }
        Write-Output "event=image_verified image=$($image.Name) tag=$Tag architecture=$($img.Architecture)"
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    Invoke-VerifyImages -Registry $Registry -Tag $Tag
}
