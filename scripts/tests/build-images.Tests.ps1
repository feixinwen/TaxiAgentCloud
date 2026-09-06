BeforeAll {
    . (Join-Path $PSScriptRoot '..\build-images.ps1')
}

Describe 'Get-TaxiAgentImageDefinitions' {
    It 'returns exactly 7 Java image definitions' {
        $images = Get-TaxiAgentImageDefinitions
        $images.Count | Should -Be 7
    }

    It 'contains gateway and agent-service' {
        $images = Get-TaxiAgentImageDefinitions
        $images.Name | Should -Contain 'taxiagent-gateway'
        $images.Name | Should -Contain 'taxiagent-agent-service'
    }

    It 'points at packaged jars under target/' {
        $images = Get-TaxiAgentImageDefinitions
        $images.JarPath | ForEach-Object { $_ | Should -Match 'target/.+\.jar$' }
    }

    It 'keeps image names and jar paths unique' {
        $images = Get-TaxiAgentImageDefinitions
        ($images.Name | Sort-Object -Unique).Count | Should -Be $images.Count
        ($images.JarPath | Sort-Object -Unique).Count | Should -Be $images.Count
    }
}

Describe 'Assert-ImageTag' {
    It 'rejects non-SHA tags' {
        { Assert-ImageTag 'main' } | Should -Throw
        { Assert-ImageTag '' } | Should -Throw
    }

    It 'accepts a 40-char lowercase hex SHA' {
        { Assert-ImageTag '0123456789012345678901234567890123456789' } | Should -Not -Throw
    }
}
