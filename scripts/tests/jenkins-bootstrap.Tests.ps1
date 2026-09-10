BeforeAll {
    $repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
    $jenkinsDir = Join-Path $repoRoot 'deploy/k8s/bootstrap/jenkins'
    $values = Join-Path $jenkinsDir 'values.yaml'
    $rbac = Join-Path $jenkinsDir 'rbac.yaml'
    $install = Join-Path $repoRoot 'scripts/server/install-jenkins.sh'
}

Describe 'Jenkins controller values' {
    It 'pins the controller image and disables controller executors' {
        $v = Get-Content -Raw $values
        $v | Should -Match 'repository: jenkins/jenkins'
        $v | Should -Match 'tag: "2\.568\.2-jdk21"'
        $v | Should -Match 'numExecutors:\s*0'
    }

    It 'exposes NodePort 30080 with a 10Gi home' {
        $v = Get-Content -Raw $values
        $v | Should -Match '30080'
        $v | Should -Match '10Gi'
    }

    It 'references secrets instead of embedding credentials' {
        $v = Get-Content -Raw $values
        $v | Should -Match 'existingSecret|secretKeyRef|passwordSecret'
        $v | Should -Not -Match 'password:\s*\S+'
        $v | Should -Not -Match 'token:\s*\S+'
    }

    It 'configures proxy NO_PROXY for cluster and ZeroTier ranges' {
        $v = Get-Content -Raw $values
        $v | Should -Match 'NO_PROXY'
        $v | Should -Match '\.svc'
        $v | Should -Match '10\.42\.0\.0/16'
        $v | Should -Match '10\.43\.0\.0/16'
        $v | Should -Match '10\.243\.0\.0/16'
    }

    It 'declares the taxiagent-deploy pipeline job with GIT_REF and RUN_E2E' {
        $v = Get-Content -Raw $values
        $v | Should -Match 'taxiagent-deploy'
        $v | Should -Match 'GIT_REF'
        $v | Should -Match 'RUN_E2E'
        $v | Should -Match 'Jenkinsfile'
    }

    It 'executes Job DSL through DslScriptLoader, not bare workflowJob' {
        $v = Get-Content -Raw $values
        $v | Should -Match 'DslScriptLoader'
        $v | Should -Match 'JenkinsJobManagement'
    }
}

Describe 'Jenkins RBAC' {
    It 'grants pod management in jenkins namespace only' {
        $r = Get-Content -Raw $rbac
        $r | Should -Match 'namespace: jenkins'
        $r | Should -Match '"pods"'
        $r | Should -Match 'delete'
    }

    It 'grants deploy rights in taxiagent without secret access' {
        $r = Get-Content -Raw $rbac
        $r | Should -Match 'namespace: taxiagent'
        $r | Should -Match 'deployments'
        $r | Should -Not -Match 'cluster-admin'
        $r | Should -Not -Match 'resources:.*secrets'
    }
}

Describe 'install-jenkins.sh' {
    It 'installs the pinned chart and waits for readiness' {
        $s = Get-Content -Raw $install
        $s | Should -Match 'helm upgrade --install'
        $s | Should -Match 'CHART_VERSION="5\.9\.53"'
        $s | Should -Match 'rollout status statefulset/jenkins'
        $s | Should -Match '30080'
    }

    It 'prints the ZeroTier URL but never credentials' {
        $s = Get-Content -Raw $install
        $s | Should -Match '10\.243\.194\.108:30080'
        $s | Should -Not -Match 'echo.*\$ADMIN_PASSWORD'
        $s | Should -Not -Match 'log.*\$ADMIN_PASSWORD'
    }
}
