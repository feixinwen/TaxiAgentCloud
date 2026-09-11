BeforeAll {
    $repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
    $agentPod = Join-Path $repoRoot 'deploy/jenkins/agent-pod.yaml'
    $buildkitConfig = Join-Path $repoRoot 'deploy/jenkins/buildkitd.toml'
    $buildkitKustomization = Join-Path $repoRoot 'deploy/jenkins/kustomization.yaml'
    $install = Join-Path $repoRoot 'scripts/server/install-jenkins.sh'
    $cacheImages = Join-Path $repoRoot 'scripts/server/cache-jenkins-agent-images.sh'
}

Describe 'Jenkins dynamic agent templates' {
    It 'provides separate test and build agent Pod templates' {
        Test-Path $agentPod | Should -BeTrue
        $yaml = Get-Content -Raw $agentPod
        ([regex]::Matches($yaml, '(?m)^kind:\s*Pod$')).Count | Should -Be 2
        $yaml | Should -Match 'name:\s*taxiagent-test-agent'
        $yaml | Should -Match 'name:\s*taxiagent-build-agent'
    }

    It 'limits CI data access to the Maven test agent' {
        $documents = (Get-Content -Raw $agentPod) -split '(?m)^---\s*$'
        $testAgent = $documents | Where-Object { $_ -match 'name:\s*taxiagent-test-agent' }
        $buildAgent = $documents | Where-Object { $_ -match 'name:\s*taxiagent-build-agent' }

        $testAgent | Should -Match 'taxiagent\.io/ci-data-access:\s*''true'''
        $testAgent | Should -Match 'name:\s*maven'
        $testAgent | Should -Match 'name:\s*taxiagent-ci-secrets'
        $testAgent | Should -Match 'mysql\.taxiagent-ci\.svc\.cluster\.local'
        $testAgent | Should -Match 'redis\.taxiagent-ci\.svc\.cluster\.local'
        $testAgent | Should -Match 'mongodb\.taxiagent-ci\.svc\.cluster\.local'
        $testAgent | Should -Match 'elasticsearch\.taxiagent-ci\.svc\.cluster\.local'

        $buildAgent | Should -Not -Match 'taxiagent\.io/ci-data-access'
        $buildAgent | Should -Not -Match 'taxiagent-ci-secrets'
        $buildAgent | Should -Not -Match 'taxiagent-ci\.svc\.cluster\.local'
    }

    It 'pins Docker Hub tools to the internal registry and other required images' {
        $yaml = Get-Content -Raw $agentPod
        ([regex]::Matches($yaml, '10\.243\.194\.108:30500/taxiagent-ci/jenkins-inbound-agent:3385\.vf1123fb_515da_-1-jdk21')).Count | Should -Be 2
        $yaml | Should -Match '10\.243\.194\.108:30500/taxiagent-ci/maven:3\.9\.11-eclipse-temurin-21'
        $yaml | Should -Match '10\.243\.194\.108:30500/taxiagent-ci/node:22-bookworm-slim'
        $yaml | Should -Match '10\.243\.194\.108:30500/taxiagent-ci/buildkit:v0\.30\.0-rootless'
        $yaml | Should -Match 'registry\.k8s\.io/kubectl:v1\.36\.4'
        $yaml | Should -Not -Match 'image:\s*docker\.io/(?:jenkins/inbound-agent|library/(?:maven|node))'
    }

    It 'shares a bounded workspace and resource limits in every container' {
        $yaml = Get-Content -Raw $agentPod
        ([regex]::Matches($yaml, 'mountPath:\s*/home/jenkins/agent')).Count | Should -Be 6
        ([regex]::Matches($yaml, '(?m)^\s+resources:$')).Count | Should -Be 6
        ([regex]::Matches($yaml, 'activeDeadlineSeconds:\s*3600')).Count | Should -Be 2
        ([regex]::Matches($yaml, 'emptyDir:')).Count | Should -BeGreaterOrEqual 3
    }

    It 'uses rootless BuildKit without Docker socket or privileged containers' {
        $yaml = Get-Content -Raw $agentPod
        $yaml | Should -Match 'runAsNonRoot:\s*true'
        ([regex]::Matches($yaml, 'runAsUser:\s*1000')).Count | Should -Be 6
        ([regex]::Matches($yaml, 'runAsGroup:\s*1000')).Count | Should -Be 6
        $yaml | Should -Match 'oci-worker-no-process-sandbox'
        $yaml | Should -Match 'seccompProfile:\s*\r?\n\s+type:\s*Unconfined'
        $yaml | Should -Not -Match 'privileged:\s*true'
        $yaml | Should -Not -Match '/var/run/docker\.sock'
        $yaml | Should -Not -Match 'docker:dind|docker daemon'
    }

    It 'mounts registry credentials read-only only in the build agent' {
        $documents = (Get-Content -Raw $agentPod) -split '(?m)^---\s*$'
        $testAgent = $documents | Where-Object { $_ -match 'name:\s*taxiagent-test-agent' }
        $buildAgent = $documents | Where-Object { $_ -match 'name:\s*taxiagent-build-agent' }

        $buildAgent | Should -Match 'secretName:\s*jenkins-registry'
        $buildAgent | Should -Match 'readOnly:\s*true'
        $testAgent | Should -Not -Match 'jenkins-registry'
    }

    It 'injects proxy settings only into download and image build containers' {
        $yaml = Get-Content -Raw $agentPod
        ([regex]::Matches($yaml, 'name:\s*HTTPS_PROXY')).Count | Should -Be 3
        $yaml | Should -Match '10\.42\.0\.0/16'
        $yaml | Should -Match '10\.43\.0\.0/16'
        $yaml | Should -Match '10\.243\.0\.0/16'
        $yaml | Should -Match '\.svc,\.cluster\.local'
    }
}

Describe 'Jenkins agent image cache' {
    It 'mirrors every Docker Hub agent image through the approved source' {
        Test-Path $cacheImages | Should -BeTrue
        $script = Get-Content -Raw $cacheImages

        $script | Should -Match 'SOURCE_REGISTRY=.*docker\.1panel\.live'
        $script | Should -Match 'jenkins/inbound-agent:3385\.vf1123fb_515da_-1-jdk21'
        $script | Should -Match 'library/maven:3\.9\.11-eclipse-temurin-21'
        $script | Should -Match 'library/node:22-bookworm-slim'
        $script | Should -Match 'moby/buildkit:v0\.30\.0-rootless'
        $script | Should -Match 'PUSH_REGISTRY=.*localhost:30500'
        $script | Should -Match 'TARGET_REPOSITORY=.*taxiagent-ci'
        $script | Should -Match 'PULL_TIMEOUT_SECONDS=.*600'
        $script | Should -Match 'timeout.*PULL_TIMEOUT_SECONDS.*docker pull'
        $script | Should -Match 'docker push'
        $script | Should -Not -Match '(?i)password\s*='
    }
}

Describe 'Rootless BuildKit configuration' {
    It 'limits concurrency and configures the internal registry without credentials' {
        Test-Path $buildkitConfig | Should -BeTrue
        $config = Get-Content -Raw $buildkitConfig
        $config | Should -Match 'max-parallelism\s*=\s*2'
        $config | Should -Match 'taxiagent-registry\.registry\.svc\.cluster\.local:5000'
        $config | Should -Match 'http\s*=\s*true'
        $config | Should -Not -Match '(?i)password|token|secret'
    }

    It 'is installed as a stable ConfigMap during Jenkins bootstrap' {
        $kustomization = Get-Content -Raw $buildkitKustomization
        $installer = Get-Content -Raw $install

        $kustomization | Should -Match 'disableNameSuffixHash:\s*true'
        $kustomization | Should -Match 'name:\s*jenkins-buildkit-config'
        $kustomization | Should -Match 'buildkitd\.toml'
        $installer | Should -Match 'kubectl apply -k.*deploy/jenkins'
    }
}
