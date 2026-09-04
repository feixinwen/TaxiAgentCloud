[CmdletBinding()]
param(
    [ValidateSet("Start", "Status", "Stop")]
    [string]$Action = "Start",

    [ValidateRange(30, 300)]
    [int]$TimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path (Join-Path $scriptRoot "..")).Path
$runtimeDirectory = Join-Path $projectRoot "logs\local"
$mavenWrapper = Join-Path $projectRoot "mvnw.cmd"
$nacosDirectory = Join-Path $projectRoot "deploy\docker-compose\nacos"
$mysqlDirectory = Join-Path $projectRoot "deploy\docker-compose\mysql"
$redisDirectory = Join-Path $projectRoot "deploy\docker-compose\redis"
$mailpitDirectory = Join-Path $projectRoot "deploy\docker-compose\mailpit"
$rocketmqDirectory = Join-Path $projectRoot "deploy\docker-compose\rocketmq"
$mongoDirectory = Join-Path $projectRoot "deploy\docker-compose\mongodb"
$nacosEnvFile = Join-Path $nacosDirectory ".env"
$mysqlEnvFile = Join-Path $mysqlDirectory ".env"
$redisEnvFile = Join-Path $redisDirectory ".env"
$mailpitEnvFile = Join-Path $mailpitDirectory ".env"
$rocketmqEnvFile = Join-Path $rocketmqDirectory ".env"
$nacosComposeFile = Join-Path $nacosDirectory "compose.yml"
$mysqlComposeFile = Join-Path $mysqlDirectory "compose.yml"
$mysqlInitScript = Join-Path $mysqlDirectory "init\01-create-service-databases.sql"
$redisComposeFile = Join-Path $redisDirectory "compose.yml"
$mailpitComposeFile = Join-Path $mailpitDirectory "compose.yml"
$rocketmqComposeFile = Join-Path $rocketmqDirectory "compose.yml"
$elasticsearchDirectory = Join-Path $projectRoot "deploy\docker-compose\elasticsearch"
$elasticsearchComposeFile = Join-Path $elasticsearchDirectory "compose.yml"
$mongoComposeFile = Join-Path $mongoDirectory "compose.yml"
$jwtKeyGenerator = Join-Path $scriptRoot "generate-local-jwt-keys.ps1"
$jwtPrivateKeyPath = Join-Path $projectRoot "secrets\local\auth-jwt-private.pem"
$jwtPublicKeyPath = Join-Path $projectRoot "secrets\local\auth-jwt-public.pem"

$javaServices = @(
    [pscustomobject]@{
        Name = "taxiagent-user-service"
        Module = "services/taxiagent-user-service"
        Port = 8081
        HealthUrl = "http://127.0.0.1:8081/api/users/ping"
    },
    [pscustomobject]@{
        Name = "taxiagent-auth-service"
        Module = "services/taxiagent-auth-service"
        Port = 8082
        HealthUrl = "http://127.0.0.1:8082/api/auth/ping"
    },
    [pscustomobject]@{
        Name = "taxiagent-order-service"
        Module = "services/taxiagent-order-service"
        Port = 8083
        HealthUrl = "http://127.0.0.1:8083/api/orders/ping"
    },
    [pscustomobject]@{
        Name = "taxiagent-ticket-service"
        Module = "services/taxiagent-ticket-service"
        Port = 8084
        HealthUrl = "http://127.0.0.1:8084/api/tickets/ping"
    },
    [pscustomobject]@{
        Name = "taxiagent-rag-service"
        Module = "services/taxiagent-rag-service"
        Port = 8086
        HealthUrl = "http://127.0.0.1:8086/api/rag/ping"
    },
    [pscustomobject]@{
        Name = "taxiagent-agent-service"
        Module = "services/taxiagent-agent-service"
        Port = 8087
        HealthUrl = "http://127.0.0.1:8087/agent/health"
    },
    [pscustomobject]@{
        Name = "taxiagent-gateway"
        Module = "gateway/taxiagent-gateway"
        Port = 9000
        HealthUrl = "http://127.0.0.1:9000/actuator/health"
    }
)

function Write-Step {
    param([string]$Message)

    Write-Host "[TaxiAgent] $Message" -ForegroundColor Cyan
}

function Read-DotEnv {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing local configuration: $Path. Copy .env.example to .env and set local values."
    }

    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#") -or -not $trimmed.Contains("=")) {
            continue
        }

        $parts = $trimmed.Split("=", 2)
        $key = $parts[0].Trim()
        $value = $parts[1].Trim()
        $isDoubleQuoted = $value.StartsWith('"') -and $value.EndsWith('"')
        $isSingleQuoted = $value.StartsWith("'") -and $value.EndsWith("'")
        if ($value.Length -ge 2 -and ($isDoubleQuoted -or $isSingleQuoted)) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$key] = $value
    }
    return $values
}

function Get-ConfiguredValue {
    param(
        [hashtable]$Values,
        [string]$Name,
        [string]$DefaultValue = ""
    )

    $processValue = [Environment]::GetEnvironmentVariable($Name, "Process")
    if (-not [string]::IsNullOrWhiteSpace($processValue)) {
        return $processValue
    }
    if ($Values.ContainsKey($Name)) {
        return [string]$Values[$Name]
    }
    return $DefaultValue
}

function Assert-CommandAvailable {
    param([string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Command not found: $Name. Install it and add it to PATH."
    }
}

function Test-TcpPort {
    param([int]$Port)

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync("127.0.0.1", $Port)
        return $task.Wait(300) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-ContainerHealthy {
    param(
        [string]$ContainerName,
        [int]$Timeout
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($Timeout)
    while ([DateTime]::UtcNow -lt $deadline) {
        [string]$health = (& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $ContainerName 2>$null)
        if ($LASTEXITCODE -eq 0 -and $health.Trim() -eq "healthy") {
            Write-Step "$ContainerName is healthy"
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$ContainerName did not become healthy within $Timeout seconds. Run: docker logs $ContainerName"
}

function Ensure-AgentDatabase {
    param(
        [string]$InitScriptPath
    )

    if (-not (Test-Path -LiteralPath $InitScriptPath -PathType Leaf)) {
        throw "MySQL database initialization script not found: $InitScriptPath"
    }

    Write-Step "Ensuring service databases and grants for the retained MySQL volume"
    [string]$databaseSql = Get-Content -LiteralPath $InitScriptPath -Raw -Encoding UTF8
    $databaseSql | & docker exec --interactive taxiagent-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --batch'
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL retained-volume database upgrade failed."
    }

    & docker exec taxiagent-mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user=taxiagent --database=taxiagent_agent --batch --skip-column-names --execute=SELECT+1' *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL permission verification for taxiagent_agent failed."
    }
    Write-Step "taxiagent_agent database and taxiagent user grant are ready"
}

function Wait-TcpHealthy {
    param(
        [string]$Name,
        [int]$Port,
        [int]$Timeout
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($Timeout)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-TcpPort -Port $Port) {
            Write-Step "$Name is ready on port $Port"
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$Name did not become reachable on port $Port within $Timeout seconds."
}

function Wait-HttpHealthy {
    param(
        [string]$Name,
        [string]$Url,
        [int]$Timeout,
        [System.Diagnostics.Process]$Process
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($Timeout)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            throw "$Name exited with code $($Process.ExitCode). Check logs in $runtimeDirectory."
        }
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                Write-Step "$Name is ready: $Url"
                return
            }
        } catch {
            # Connection failures are expected while the service is starting.
        }
        Start-Sleep -Seconds 2
    }
    throw "$Name was not ready within $Timeout seconds. Check logs in $runtimeDirectory."
}

function Invoke-WithProcessEnvironment {
    param(
        [hashtable]$EnvironmentValues,
        [scriptblock]$Script
    )

    $previousValues = @{}
    try {
        foreach ($entry in $EnvironmentValues.GetEnumerator()) {
            $previousValues[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, "Process")
            [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, "Process")
        }
        return & $Script
    } finally {
        foreach ($entry in $previousValues.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
        }
    }
}

function Start-JavaService {
    param(
        [pscustomobject]$Service,
        [hashtable]$EnvironmentValues
    )

    if (Test-TcpPort -Port $Service.Port) {
        throw "Port $($Service.Port) is already in use. Stop the existing process before starting $($Service.Name)."
    }

    New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
    $pidFile = Join-Path $runtimeDirectory "$($Service.Name).pid"
    $standardLog = Join-Path $runtimeDirectory "$($Service.Name).out.log"
    $errorLog = Join-Path $runtimeDirectory "$($Service.Name).err.log"

    if (Test-Path -LiteralPath $pidFile) {
        Remove-Item -LiteralPath $pidFile -Force
    }

    Write-Step "Starting $($Service.Name)"
    $process = Invoke-WithProcessEnvironment -EnvironmentValues $EnvironmentValues -Script {
        $command = "`"$mavenWrapper`" --batch-mode -pl $($Service.Module) spring-boot:run"
        Start-Process `
            -FilePath "cmd.exe" `
            -ArgumentList @("/d", "/c", $command) `
            -WorkingDirectory $projectRoot `
            -WindowStyle Hidden `
            -RedirectStandardOutput $standardLog `
            -RedirectStandardError $errorLog `
            -PassThru
    }

    Set-Content -LiteralPath $pidFile -Value $process.Id -Encoding ASCII
    Wait-HttpHealthy -Name $Service.Name -Url $Service.HealthUrl -Timeout $TimeoutSeconds -Process $process
}

function Stop-JavaService {
    param([pscustomobject]$Service)

    $pidFile = Join-Path $runtimeDirectory "$($Service.Name).pid"
    if (-not (Test-Path -LiteralPath $pidFile -PathType Leaf)) {
        Write-Step "$($Service.Name) has no PID file; skipping"
        return
    }

    $pidText = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    if ($pidText -notmatch '^\d+$') {
        throw "Invalid PID in $pidFile. Refusing to stop a process."
    }

    $processId = [int]$pidText
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
    if ($null -ne $processInfo) {
        $expectedModule = $Service.Module
        $alternateModule = $Service.Module.Replace('/', '\')
        $moduleMatches = $processInfo.CommandLine -like "*$expectedModule*" -or
            $processInfo.CommandLine -like "*$alternateModule*"
        if (-not $moduleMatches -or
                $processInfo.CommandLine -notlike "*spring-boot:run*") {
            throw "PID $processId does not match $($Service.Name). Refusing to stop it."
        }
        Write-Step "Stopping $($Service.Name)"
        & taskkill.exe /PID $processId /T /F | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to stop $($Service.Name)."
        }
    }
    Remove-Item -LiteralPath $pidFile -Force
}

function Start-Infrastructure {
    Assert-CommandAvailable -Name "docker"
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot connect to Docker. Start Docker Desktop first."
    }

    Write-Step "Starting Nacos"
    & docker compose --env-file $nacosEnvFile -f $nacosComposeFile up -d
    if ($LASTEXITCODE -ne 0) {
        throw "Nacos Docker Compose startup failed."
    }

    Write-Step "Starting MySQL"
    & docker compose --env-file $mysqlEnvFile -f $mysqlComposeFile up -d
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL Docker Compose startup failed."
    }

    Write-Step "Starting Redis"
    if (Test-Path -LiteralPath $redisEnvFile -PathType Leaf) {
        & docker compose --env-file $redisEnvFile -f $redisComposeFile up -d
    } else {
        & docker compose -f $redisComposeFile up -d
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Redis Docker Compose startup failed."
    }

    Write-Step "Starting Mailpit"
    if (Test-Path -LiteralPath $mailpitEnvFile -PathType Leaf) {
        & docker compose --env-file $mailpitEnvFile -f $mailpitComposeFile up -d
    } else {
        & docker compose -f $mailpitComposeFile up -d
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Mailpit Docker Compose startup failed."
    }

    Write-Step "Starting RocketMQ"
    if (Test-Path -LiteralPath $rocketmqEnvFile -PathType Leaf) {
        & docker compose --env-file $rocketmqEnvFile -f $rocketmqComposeFile up -d
    } else {
        & docker compose -f $rocketmqComposeFile up -d
    }
    if ($LASTEXITCODE -ne 0) {
        throw "RocketMQ Docker Compose startup failed."
    }

    Write-Step "Starting Elasticsearch (with IK plugin)"
    & docker compose -f $elasticsearchComposeFile up -d --build
    if ($LASTEXITCODE -ne 0) {
        throw "Elasticsearch Docker Compose startup failed."
    }

    Write-Step "Starting MongoDB"
    & docker compose -f $mongoComposeFile up -d
    if ($LASTEXITCODE -ne 0) {
        throw "MongoDB Docker Compose startup failed."
    }

    Wait-ContainerHealthy -ContainerName "taxiagent-nacos" -Timeout $TimeoutSeconds
    Wait-ContainerHealthy -ContainerName "taxiagent-mysql" -Timeout $TimeoutSeconds
    Ensure-AgentDatabase -InitScriptPath $mysqlInitScript
    Wait-ContainerHealthy -ContainerName "taxiagent-redis" -Timeout $TimeoutSeconds
    Wait-TcpHealthy -Name "taxiagent-mailpit" -Port $mailpitSmtpPort -Timeout $TimeoutSeconds
    Wait-TcpHealthy -Name "taxiagent-rocketmq-namesrv" -Port 9876 -Timeout $TimeoutSeconds
    Wait-TcpHealthy -Name "taxiagent-elasticsearch" -Port 9200 -Timeout $TimeoutSeconds
    Wait-TcpHealthy -Name "taxiagent-mongo" -Port 27017 -Timeout $TimeoutSeconds
}

function Stop-Infrastructure {
    Write-Step "Stopping MySQL (volumes are retained)"
    & docker compose --env-file $mysqlEnvFile -f $mysqlComposeFile down
    Write-Step "Stopping Nacos (volumes are retained)"
    & docker compose --env-file $nacosEnvFile -f $nacosComposeFile down
    Write-Step "Stopping Redis (volumes are retained)"
    & docker compose -f $redisComposeFile down
    Write-Step "Stopping Mailpit (volumes are retained)"
    & docker compose -f $mailpitComposeFile down
    Write-Step "Stopping RocketMQ (volumes are retained)"
    & docker compose -f $rocketmqComposeFile down
    Write-Step "Stopping Elasticsearch (volumes are retained)"
    & docker compose -f $elasticsearchComposeFile down
    Write-Step "Stopping MongoDB (volumes are retained)"
    & docker compose -f $mongoComposeFile down
}

function Show-Status {
    Write-Host ""
    Write-Host "Local service status" -ForegroundColor Yellow
    foreach ($service in $javaServices) {
        $state = if (Test-TcpPort -Port $service.Port) { "LISTENING" } else { "STOPPED" }
        Write-Host ("{0,-28} port {1,-5} {2}" -f $service.Name, $service.Port, $state)
    }
    foreach ($portInfo in @(
            [pscustomobject]@{ Name = "taxiagent-nacos"; Port = 8848 },
            [pscustomobject]@{ Name = "taxiagent-mysql"; Port = 3307 },
            [pscustomobject]@{ Name = "taxiagent-redis"; Port = 6379 },
            [pscustomobject]@{ Name = "taxiagent-mailpit"; Port = 1025 },
            [pscustomobject]@{ Name = "taxiagent-rocketmq-namesrv"; Port = 9876 },
            [pscustomobject]@{ Name = "taxiagent-mongo"; Port = 27017 },
            [pscustomobject]@{ Name = "taxiagent-elasticsearch"; Port = 9200 }
        )) {
        $state = if (Test-TcpPort -Port $portInfo.Port) { "LISTENING" } else { "STOPPED" }
        Write-Host ("{0,-28} port {1,-5} {2}" -f $portInfo.Name, $portInfo.Port, $state)
    }
}

function Wait-GatewayRoute {
    param(
        [string]$Url,
        [int]$Timeout
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($Timeout)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -eq 200) {
                Write-Step "Gateway route is ready: $Url"
                return
            }
        } catch {
            # Nacos registration and Gateway discovery refresh can take a few seconds.
        }
        Start-Sleep -Seconds 2
    }
    throw "Gateway route was not ready within $Timeout seconds: $Url"
}

if ($Action -eq "Status") {
    Show-Status
    exit 0
}

if ($Action -eq "Stop") {
    Assert-CommandAvailable -Name "docker"
    [array]::Reverse($javaServices)
    foreach ($service in $javaServices) {
        Stop-JavaService -Service $service
    }
    Stop-Infrastructure
    Show-Status
    exit 0
}

if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) {
    throw "Maven Wrapper not found: $mavenWrapper"
}

$nacosValues = Read-DotEnv -Path $nacosEnvFile
$mysqlValues = Read-DotEnv -Path $mysqlEnvFile
$mysqlPassword = Get-ConfiguredValue -Values $mysqlValues -Name "MYSQL_PASSWORD"
if ([string]::IsNullOrWhiteSpace($mysqlPassword)) {
    throw "MYSQL_PASSWORD is not configured."
}
$mysqlPort = Get-ConfiguredValue -Values $mysqlValues -Name "MYSQL_HOST_PORT" -DefaultValue "3307"
$redisValues = @{}
if (Test-Path -LiteralPath $redisEnvFile -PathType Leaf) {
    $redisValues = Read-DotEnv -Path $redisEnvFile
}
$mailpitValues = @{}
if (Test-Path -LiteralPath $mailpitEnvFile -PathType Leaf) {
    $mailpitValues = Read-DotEnv -Path $mailpitEnvFile
}
$redisHostPort = Get-ConfiguredValue -Values $redisValues -Name "REDIS_HOST_PORT" -DefaultValue "6379"
$mailpitSmtpPort = Get-ConfiguredValue -Values $mailpitValues -Name "MAILPIT_SMTP_PORT" -DefaultValue "1025"
$mailpitUiPort = Get-ConfiguredValue -Values $mailpitValues -Name "MAILPIT_UI_PORT" -DefaultValue "8025"
$nacosUsername = Get-ConfiguredValue -Values $nacosValues -Name "NACOS_USERNAME" -DefaultValue "nacos"
$nacosPassword = Get-ConfiguredValue -Values $nacosValues -Name "NACOS_PASSWORD"
if ([string]::IsNullOrWhiteSpace($nacosPassword)) {
    throw "NACOS_PASSWORD is not configured. Add NACOS_USERNAME and NACOS_PASSWORD to deploy/docker-compose/nacos/.env."
}

foreach ($service in $javaServices) {
    if (Test-TcpPort -Port $service.Port) {
        throw "Port $($Service.Port) is already in use. Run -Action Status and stop existing Java services."
    }
}

Start-Infrastructure

if (-not (Test-Path -LiteralPath $jwtPrivateKeyPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $jwtPublicKeyPath -PathType Leaf)) {
    Write-Step "Generating local-only RSA keys for Auth JWT"
    & $jwtKeyGenerator
}

# Load missing API keys from gitignored secrets/local/*.env (dashscope.env, deepseek.env, qweather.env).
# Explicitly set environment variables take precedence and are never overwritten.
$secretsDirectory = Join-Path $projectRoot "secrets\local"
foreach ($secretsFile in @("dashscope.env", "deepseek.env", "qweather.env", "amap.env")) {
    $secretsPath = Join-Path $secretsDirectory $secretsFile
    if (-not (Test-Path -LiteralPath $secretsPath -PathType Leaf)) {
        continue
    }
    foreach ($line in Get-Content -LiteralPath $secretsPath -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#") -or -not $trimmed.Contains("=")) {
            continue
        }
        $name = $trimmed.Split("=", 2)[0].Trim()
        if (-not [Environment]::GetEnvironmentVariable($name, "Process")) {
            [Environment]::SetEnvironmentVariable($name, $trimmed.Split("=", 2)[1].Trim(), "Process")
        }
    }
}

$commonEnvironment = @{
    NACOS_SERVER_ADDR = "127.0.0.1:8848"
    NACOS_USERNAME = $nacosUsername
    NACOS_PASSWORD = $nacosPassword
}
$userEnvironment = $commonEnvironment.Clone()
$userEnvironment["USER_DB_URL"] = "jdbc:mysql://127.0.0.1:$mysqlPort/taxiagent_user?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$userEnvironment["USER_DB_USERNAME"] = "taxiagent"
$userEnvironment["USER_DB_PASSWORD"] = $mysqlPassword
$userEnvironment["USER_JWT_PUBLIC_KEY_LOCATION"] = ([System.Uri]::new($jwtPublicKeyPath)).AbsoluteUri
$userEnvironment["USER_JWT_SERVICE_AUDIENCE"] = "taxiagent-user-service"
$userEnvironment["USER_AUTH_SERVICE_ID"] = "taxiagent-auth-service"
$authEnvironment = $commonEnvironment.Clone()
$authEnvironment["AUTH_DB_URL"] = "jdbc:mysql://127.0.0.1:$mysqlPort/taxiagent_auth?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$authEnvironment["AUTH_DB_USERNAME"] = "taxiagent"
$authEnvironment["AUTH_DB_PASSWORD"] = $mysqlPassword
$authEnvironment["AUTH_JWT_PRIVATE_KEY_LOCATION"] = ([System.Uri]::new($jwtPrivateKeyPath)).AbsoluteUri
$authEnvironment["AUTH_JWT_PUBLIC_KEY_LOCATION"] = ([System.Uri]::new($jwtPublicKeyPath)).AbsoluteUri
$authEnvironment["AUTH_SERVICE_ID"] = "taxiagent-auth-service"
$authEnvironment["AUTH_USER_SERVICE_AUDIENCE"] = "taxiagent-user-service"
$authEnvironment["AUTH_ID_WORKER_ID"] = "0"
$authEnvironment["AUTH_ID_DATACENTER_ID"] = "0"
$authEnvironment["SPRING_DATA_REDIS_HOST"] = "127.0.0.1"
$authEnvironment["SPRING_DATA_REDIS_PORT"] = $redisHostPort
$authEnvironment["SPRING_MAIL_HOST"] = "127.0.0.1"
$authEnvironment["SPRING_MAIL_PORT"] = $mailpitSmtpPort
$authEnvironment["MAIL_FROM"] = "taxiagent@test.local"
$authEnvironment["ROCKETMQ_ENABLED"] = "true"
$orderEnvironment = $commonEnvironment.Clone()
$orderEnvironment["ORDER_DB_URL"] = "jdbc:mysql://127.0.0.1:$mysqlPort/taxiagent_order?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$orderEnvironment["ORDER_DB_USERNAME"] = "taxiagent"
$orderEnvironment["ORDER_DB_PASSWORD"] = $mysqlPassword
$orderEnvironment["ORDER_MONGODB_URI"] = "mongodb://127.0.0.1:27017/taxiagent_order"
$orderEnvironment["ORDER_JWT_PUBLIC_KEY_LOCATION"] = ([System.Uri]::new($jwtPublicKeyPath)).AbsoluteUri
$orderEnvironment["ORDER_JWT_SERVICE_AUDIENCE"] = "taxiagent-order-service"
$orderEnvironment["ORDER_ID_WORKER_ID"] = "2"
$orderEnvironment["ORDER_ID_DATACENTER_ID"] = "0"
$orderEnvironment["SPRING_DATA_REDIS_HOST"] = "127.0.0.1"
$orderEnvironment["SPRING_DATA_REDIS_PORT"] = $redisHostPort
# Pass AMAP_KEY through if set in the environment; otherwise leave it out (smoke will hit the 502 path)
if ($env:AMAP_KEY) {
    $orderEnvironment["AMAP_KEY"] = $env:AMAP_KEY
}
$ticketEnvironment = $commonEnvironment.Clone()
$ticketEnvironment["TICKET_DB_URL"] = "jdbc:mysql://127.0.0.1:$mysqlPort/taxiagent_ticket?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$ticketEnvironment["TICKET_DB_USERNAME"] = "taxiagent"
$ticketEnvironment["TICKET_DB_PASSWORD"] = $mysqlPassword
$ticketEnvironment["TICKET_JWT_PUBLIC_KEY_LOCATION"] = ([System.Uri]::new($jwtPublicKeyPath)).AbsoluteUri
$ticketEnvironment["TICKET_JWT_SERVICE_AUDIENCE"] = "taxiagent-ticket-service"
$ticketEnvironment["SPRING_DATA_REDIS_HOST"] = "127.0.0.1"
$ticketEnvironment["SPRING_DATA_REDIS_PORT"] = $redisHostPort
$ragEnvironment = $commonEnvironment.Clone()
$ragEnvironment["RAG_DB_URL"] = "jdbc:mysql://127.0.0.1:$mysqlPort/taxiagent_rag?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$ragEnvironment["RAG_DB_USERNAME"] = "taxiagent"
$ragEnvironment["RAG_DB_PASSWORD"] = $mysqlPassword
$ragEnvironment["RAG_ES_URIS"] = "http://127.0.0.1:9200"
$ragEnvironment["RAG_JWT_PUBLIC_KEY_LOCATION"] = ([System.Uri]::new($jwtPublicKeyPath)).AbsoluteUri
$ragEnvironment["RAG_JWT_SERVICE_AUDIENCE"] = "taxiagent-rag-service"
$ragEnvironment["RAG_ID_WORKER_ID"] = "3"
$ragEnvironment["RAG_ID_DATACENTER_ID"] = "0"
$ragEnvironment["SPRING_DATA_REDIS_HOST"] = "127.0.0.1"
$ragEnvironment["SPRING_DATA_REDIS_PORT"] = $redisHostPort
if ($env:DASHSCOPE_API_KEY) {
    $ragEnvironment["DASHSCOPE_API_KEY"] = $env:DASHSCOPE_API_KEY
}
$agentEnvironment = $commonEnvironment.Clone()
$agentEnvironment["AGENT_DB_URL"] = "jdbc:mysql://127.0.0.1:$mysqlPort/taxiagent_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$agentEnvironment["AGENT_DB_USERNAME"] = "taxiagent"
$agentEnvironment["AGENT_DB_PASSWORD"] = $mysqlPassword
$agentEnvironment["AGENT_JWT_PUBLIC_KEY_LOCATION"] = ([System.Uri]::new($jwtPublicKeyPath)).AbsoluteUri
$agentEnvironment["AGENT_ID_WORKER_ID"] = "4"
$agentEnvironment["AGENT_ID_DATACENTER_ID"] = "0"
$agentEnvironment["SPRING_DATA_REDIS_HOST"] = "127.0.0.1"
$agentEnvironment["SPRING_DATA_REDIS_PORT"] = $redisHostPort
# DashScope key is passed only through the process environment and is never printed.
if ($env:DASHSCOPE_API_KEY) {
    $agentEnvironment["DASHSCOPE_API_KEY"] = $env:DASHSCOPE_API_KEY
}
else {
    Write-Step "DASHSCOPE_API_KEY is not set; Agent intent classification will fail closed (fixed phrase routing)"
}
# DeepSeek key is passed only through the process environment and is never printed.
if ($env:DEEPSEEK_API_KEY) {
    $agentEnvironment["DEEPSEEK_API_KEY"] = $env:DEEPSEEK_API_KEY
}
else {
    Write-Step "DEEPSEEK_API_KEY is not set; Agent model calls will return MODEL_UNAVAILABLE"
}
# Qweather keys are passed only through the process environment and are never printed.
if ($env:QWEATHER_KEY) {
    $agentEnvironment["QWEATHER_KEY"] = $env:QWEATHER_KEY
}
if ($env:QWEATHER_TOKEN) {
    $agentEnvironment["QWEATHER_TOKEN"] = $env:QWEATHER_TOKEN
}
# Amap key is passed only through the process environment and is never printed.
if ($env:AMAP_KEY) {
    $agentEnvironment["AMAP_KEY"] = $env:AMAP_KEY
}
$gatewayEnvironment = $commonEnvironment.Clone()
$gatewayEnvironment["GATEWAY_JWT_PUBLIC_KEY_LOCATION"] = ([System.Uri]::new($jwtPublicKeyPath)).AbsoluteUri

Start-JavaService -Service $javaServices[0] -EnvironmentValues $userEnvironment
Start-JavaService -Service $javaServices[1] -EnvironmentValues $authEnvironment
Start-JavaService -Service $javaServices[2] -EnvironmentValues $orderEnvironment
Start-JavaService -Service $javaServices[3] -EnvironmentValues $ticketEnvironment
Start-JavaService -Service $javaServices[4] -EnvironmentValues $ragEnvironment
Start-JavaService -Service $javaServices[5] -EnvironmentValues $agentEnvironment
Start-JavaService -Service $javaServices[6] -EnvironmentValues $gatewayEnvironment

Write-Step "Verifying Gateway routes to downstream services"
$gatewayChecks = @(
    "http://127.0.0.1:9000/api/users/ping",
    "http://127.0.0.1:9000/api/auth/ping",
    "http://127.0.0.1:9000/api/orders/ping",
    "http://127.0.0.1:9000/api/tickets/ping",
    "http://127.0.0.1:9000/api/rag/ping"
)
foreach ($url in $gatewayChecks) {
    Wait-GatewayRoute -Url $url -Timeout $TimeoutSeconds
}

Show-Status
Write-Host ""
Write-Host "Startup complete:" -ForegroundColor Green
Write-Host "  Nacos Console : http://127.0.0.1:8080/index.html"
Write-Host "  Gateway       : http://127.0.0.1:9000"
Write-Host "  User Ping     : http://127.0.0.1:9000/api/users/ping"
Write-Host "  Auth Ping     : http://127.0.0.1:9000/api/auth/ping"
Write-Host "  Order Ping    : http://127.0.0.1:9000/api/orders/ping"
Write-Host "  Ticket Ping   : http://127.0.0.1:9000/api/tickets/ping"
Write-Host "  Rag Ping      : http://127.0.0.1:9000/api/rag/ping"
Write-Host "  Agent Health  : http://127.0.0.1:8087/agent/health"
Write-Host "  Mailpit UI    : http://127.0.0.1:$mailpitUiPort"
Write-Host "  RocketMQ Dashboard : http://127.0.0.1:8085"
Write-Host "  Logs          : $runtimeDirectory"
Write-Host ""
Write-Host "Stop all local services:"
Write-Host "  .\scripts\local-dev.ps1 -Action Stop"
