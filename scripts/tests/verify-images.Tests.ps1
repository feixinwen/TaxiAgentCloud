BeforeAll {
    . (Join-Path $PSScriptRoot '..\verify-images.ps1')
    function global:New-FakeImage {
        param([string]$FullTag, [string]$User = 'taxiagent', [string]$Arch = 'amd64')
        [PSCustomObject]@{
            Architecture = $Arch
            Config       = [PSCustomObject]@{ User = $User }
            RepoTags     = @($FullTag)
        }
    }
}

Describe 'Invoke-VerifyImages' {
    It 'verifies all 8 images and prints one event each' {
        Mock Get-DockerImageInspect { New-FakeImage -FullTag $ImageTag }
        $out = Invoke-VerifyImages -Registry 'localhost:5000' -Tag '0123456789012345678901234567890123456789'
        ($out | Where-Object { $_ -match '^event=image_verified' }).Count | Should -Be 8
    }

    It 'rejects a missing image' {
        Mock Get-DockerImageInspect {
            if ($ImageTag -like '*taxiagent-gateway*') { return $null }
            New-FakeImage -FullTag $ImageTag
        }
        { Invoke-VerifyImages -Registry 'localhost:5000' -Tag '0123456789012345678901234567890123456789' } |
            Should -Throw '*taxiagent-gateway*'
    }

    It 'rejects an image tagged without the requested SHA' {
        Mock Get-DockerImageInspect {
            New-FakeImage -FullTag ($ImageTag -replace ':.*', ':deadbeef')
        }
        { Invoke-VerifyImages -Registry 'localhost:5000' -Tag '0123456789012345678901234567890123456789' } |
            Should -Throw
    }

    It 'rejects root or empty container users' {
        foreach ($user in @('root', '0', '')) {
            Mock Get-DockerImageInspect { New-FakeImage -FullTag $ImageTag -User $user }
            { Invoke-VerifyImages -Registry 'localhost:5000' -Tag '0123456789012345678901234567890123456789' } |
                Should -Throw '*must be a non-root user*'
        }
    }

    It 'rejects non-amd64 images' {
        Mock Get-DockerImageInspect { New-FakeImage -FullTag $ImageTag -Arch 'arm64' }
        { Invoke-VerifyImages -Registry 'localhost:5000' -Tag '0123456789012345678901234567890123456789' } |
            Should -Throw '*amd64*'
    }
}
