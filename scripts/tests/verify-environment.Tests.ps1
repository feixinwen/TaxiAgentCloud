BeforeAll {
    $scriptsDir = Join-Path $PSScriptRoot '..\server'
    $deploy = Join-Path $scriptsDir 'deploy-environment.sh'
    $verify = Join-Path $scriptsDir 'verify-environment.sh'
}

Describe 'deploy-environment.sh' {
    It 'accepts infra-only, apps-only and all modes' {
        $s = Get-Content -Raw $deploy
        $s | Should -Match '--infra-only'
        $s | Should -Match '--apps-only'
        $s | Should -Match '--all'
    }

    It 'applies config, infra, ci-infra then apps in order' {
        $s = Get-Content -Raw $deploy
        $order = $s.Substring($s.IndexOf('# Order'))
        $order.IndexOf('config') | Should -BeLessThan $order.IndexOf('infra')
        $order.IndexOf('infra') | Should -BeLessThan $order.IndexOf('ci-infra')
        $order.IndexOf('ci-infra') | Should -BeLessThan $order.IndexOf('apps')
        $s | Should -Match '\-\-all\) infra_apply; apps_apply'
    }

    It 'waits on rollouts instead of sleeping' {
        $s = Get-Content -Raw $deploy
        $s | Should -Match 'rollout status'
        $s | Should -Not -Match 'sleep '
    }

    It 'replaces the IMAGE_TAG placeholder when deploying apps' {
        $s = Get-Content -Raw $deploy
        $s | Should -Match 'IMAGE_TAG'
        $s | Should -Match 'sed'
        $s | Should -Match 'rev-parse HEAD'
    }

    It 'dumps events and logs on failure' {
        $s = Get-Content -Raw $deploy
        $s | Should -Match 'get events'
        $s | Should -Match 'logs'
        $s | Should -Match 'exit 1'
    }
}

Describe 'verify-environment.sh' {
    It 'never modifies cluster state' {
        $s = Get-Content -Raw $verify
        $s | Should -Not -Match 'kubectl (apply|delete|create|edit|scale)'
        $s | Should -Not -Match 'sleep '
    }

    It 'checks nodes, pvcs, pods and the ingress' {
        $s = Get-Content -Raw $verify
        $s | Should -Match 'get nodes'
        $s | Should -Match 'get pvc'
        $s | Should -Match 'get pods'
        $s | Should -Match 'get ingress'
    }

    It 'probes the public entrypoint from the node' {
        $s = Get-Content -Raw $verify
        $s | Should -Match 'curl'
        $s | Should -Match '10.243.194.108'
    }
}
