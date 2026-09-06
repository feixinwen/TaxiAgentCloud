BeforeAll {
    . (Join-Path $PSScriptRoot '..\build-images.ps1')
}

Describe 'Get-TaxiAgentImageDefinitions' {
    It 'returns 8 images: 7 java services and 1 frontend' {
        $images = Get-TaxiAgentImageDefinitions
        $images.Count | Should -Be 8
        ($images | Where-Object Type -eq 'java').Count | Should -Be 7
    }

    It 'contains gateway and agent-service' {
        $images = Get-TaxiAgentImageDefinitions
        $images.Name | Should -Contain 'taxiagent-gateway'
        $images.Name | Should -Contain 'taxiagent-agent-service'
    }

    It 'defines client-service as a frontend' {
        $client = Get-TaxiAgentImageDefinitions | Where-Object Name -eq 'taxiagent-client-service'
        $client | Should -Not -BeNullOrEmpty
        $client.Type | Should -Be 'frontend'
    }

    It 'points java images at packaged jars under target/' {
        $images = Get-TaxiAgentImageDefinitions | Where-Object Type -eq 'java'
        $images.JarPath | ForEach-Object { $_ | Should -Match 'target/.+\.jar$' }
    }

    It 'keeps image names and jar paths unique' {
        $images = Get-TaxiAgentImageDefinitions
        ($images.Name | Sort-Object -Unique).Count | Should -Be $images.Count
        $java = $images | Where-Object Type -eq 'java'
        ($java.JarPath | Sort-Object -Unique).Count | Should -Be $java.Count
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

Describe 'Client nginx reverse proxy' {
    BeforeAll {
        $nginxConf = Join-Path $PSScriptRoot '..\..\services\taxiagent-client-service\nginx.conf'
        $nginx = Get-Content -Raw $nginxConf
    }

    It 'proxies /api to the K3s gateway service' {
        $nginx | Should -Match 'proxy_pass http://taxiagent-gateway:9000;'
    }

    It 'keeps SSE-friendly proxy settings' {
        $nginx | Should -Match 'proxy_buffering off;'
        $nginx | Should -Match 'proxy_read_timeout 90s;'
    }
}

Describe 'Client image runs as non-root' {
    It 'declares the nginx user in the Dockerfile' {
        $dockerfile = Get-Content -Raw (Join-Path $PSScriptRoot '..\..\services\taxiagent-client-service\Dockerfile')
        $dockerfile | Should -Match 'USER nginx'
    }
}
