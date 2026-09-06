BeforeAll {
    $repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
    $appsDir = Join-Path $repoRoot 'deploy/k8s/apps'
}

$components = @(
    'taxiagent-gateway',
    'taxiagent-user-service',
    'taxiagent-auth-service',
    'taxiagent-order-service',
    'taxiagent-ticket-service',
    'taxiagent-rag-service',
    'taxiagent-agent-service',
    'taxiagent-client-service'
)

$javaComponents = $components | Where-Object { $_ -ne 'taxiagent-client-service' }

Describe 'TaxiAgent app workloads' {
    It 'defines a deployment and service for all 8 components' {
        foreach ($c in $components) {
            (Test-Path (Join-Path $appsDir "$c/deployment.yaml")) | Should -Be $true -Because "deployment $c"
            (Test-Path (Join-Path $appsDir "$c/service.yaml")) | Should -Be $true -Because "service $c"
        }
    }

    It 'runs single replica with the IMAGE_TAG placeholder image' {
        foreach ($c in $components) {
            $d = Get-Content -Raw (Join-Path $appsDir "$c/deployment.yaml")
            $d | Should -Match "replicas: 1"
            $d | Should -Match "image: 10\.243\.194\.108:30500/taxiagent/$c:IMAGE_TAG"
        }
    }

    It 'sets rolling update, history limit and grace period on every deployment' {
        foreach ($c in $components) {
            $d = Get-Content -Raw (Join-Path $appsDir "$c/deployment.yaml")
            $d | Should -Match 'maxUnavailable: 0'
            $d | Should -Match 'maxSurge: 1'
            $d | Should -Match 'revisionHistoryLimit: 3'
            $d | Should -Match 'terminationGracePeriodSeconds: 30'
            $d | Should -Match 'configMapRef:'
            $d | Should -Match 'secretRef:'
        }
    }

    It 'points selectors and services at the component name' {
        foreach ($c in $components) {
            $d = Get-Content -Raw (Join-Path $appsDir "$c/deployment.yaml")
            $d | Should -Match "app: $c"
            $s = Get-Content -Raw (Join-Path $appsDir "$c/service.yaml")
            $s | Should -Match "name: $c"
            $s | Should -Match "app: $c"
        }
    }

    It 'gives Java services actuator startup, readiness and liveness probes' {
        foreach ($c in $javaComponents) {
            $d = Get-Content -Raw (Join-Path $appsDir "$c/deployment.yaml")
            $d | Should -Match 'startupProbe:'
            $d | Should -Match 'path: /actuator/health/readiness'
            $d | Should -Match 'path: /actuator/health/liveness'
        }
    }

    It 'mounts the JWT keys under /app/secrets' {
        foreach ($c in $javaComponents) {
            $d = Get-Content -Raw (Join-Path $appsDir "$c/deployment.yaml")
            $d | Should -Match 'mountPath: /app/secrets'
        }
    }

    It 'declares one ClusterIP ingress exposed through the client service' {
        $ingress = Get-Content -Raw (Join-Path $appsDir 'client/ingress.yaml')
        $ingress | Should -Match 'kind: Ingress'
        $ingress | Should -Not -Match 'host:'
        $ingress | Should -Match 'pathType: Prefix'
        $ingress | Should -Match 'taxiagent-client-service'
        $ingress | Should -Match 'number: 80'
    }
}
