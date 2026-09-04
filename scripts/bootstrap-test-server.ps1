param(
  [string]$Server = "10.243.194.108",
  [int]$Port = 8122,
  [string]$Proxy = "http://10.243.150.36:7897",
  [switch]$CheckOnly
)

$ErrorActionPreference = "Stop"

$remoteScript = "/tmp/taxiagent-bootstrap-k3s.sh"
$localScript = Join-Path $PSScriptRoot "server\bootstrap-k3s.sh"
if (-not (Test-Path $localScript)) { throw "local script not found: $localScript" }

Write-Host "[bootstrap] checking TCP connectivity to ${Server}:${Port}..."
$reachable = (Test-NetConnection -ComputerName $Server -Port $Port -WarningAction SilentlyContinue).TcpTestSucceeded
if (-not $reachable) { throw "cannot reach ${Server}:${Port} (ZeroTier up?)" }

Write-Host "[bootstrap] copying script to ${Server}:${remoteScript}..."
scp -P $Port $localScript "root@${Server}:${remoteScript}"
if ($LASTEXITCODE -ne 0) { throw "scp failed with exit code $LASTEXITCODE" }

$remoteArgs = @("--node-ip", $Server, "--proxy", $Proxy)
if ($CheckOnly) { $remoteArgs += "--check-only" }

Write-Host "[bootstrap] running on server: bash $remoteScript $($remoteArgs -join ' ')"
ssh -p $Port "root@$Server" "bash $remoteScript $($remoteArgs -join ' ')"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
