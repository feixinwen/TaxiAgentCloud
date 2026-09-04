[CmdletBinding()]
param(
    [string]$OutputDirectory = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
    $projectRoot = (Resolve-Path (Join-Path $scriptRoot "..")).Path
    $OutputDirectory = Join-Path $projectRoot "secrets\local"
}

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null
$privateKeyPath = Join-Path $resolvedOutput "auth-jwt-private.pem"
$publicKeyPath = Join-Path $resolvedOutput "auth-jwt-public.pem"

if ((Test-Path -LiteralPath $privateKeyPath) -or (Test-Path -LiteralPath $publicKeyPath)) {
    throw "JWT key file already exists. Refusing to overwrite local keys: $resolvedOutput"
}

if (-not (Get-Command "openssl" -ErrorAction SilentlyContinue)) {
    throw "OpenSSL was not found. Install OpenSSL and add it to PATH before generating JWT keys."
}

try {
    & openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out $privateKeyPath
    if ($LASTEXITCODE -ne 0) {
        throw "OpenSSL failed to generate the JWT private key."
    }
    & openssl pkey -in $privateKeyPath -pubout -out $publicKeyPath
    if ($LASTEXITCODE -ne 0) {
        throw "OpenSSL failed to derive the JWT public key."
    }
} catch {
    # 仅清理由本次脚本创建的两个精确目标，避免留下半套密钥。
    Remove-Item -LiteralPath $privateKeyPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $publicKeyPath -Force -ErrorAction SilentlyContinue
    throw
}

Write-Host "Local JWT key pair created:" -ForegroundColor Green
Write-Host "  Private: $privateKeyPath"
Write-Host "  Public : $publicKeyPath"
Write-Host "These files are under secrets/ and must never be committed."
