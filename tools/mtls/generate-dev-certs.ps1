param(
    [string]$OutputDir = "",
    [string]$Password = "changeit",
    [string]$BackendDns = "localhost",
    [string]$BackendIp = "127.0.0.1",
    [string]$ServerId = "10000000-0000-0000-0000-000000000001"
)

$ErrorActionPreference = "Stop"

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found in PATH."
    }
}

Require-Command "openssl"
Require-Command "keytool"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $repoRoot "tools\mtls\out"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputDir)) {
    $OutputDir = Join-Path $repoRoot $OutputDir
}

$OutputDir = (New-Item -ItemType Directory -Force -Path $OutputDir).FullName

$caKey = Join-Path $OutputDir "ca.key"
$caCert = Join-Path $OutputDir "ca.crt"
$backendKey = Join-Path $OutputDir "backend.key"
$backendCsr = Join-Path $OutputDir "backend.csr"
$backendCrt = Join-Path $OutputDir "backend.crt"
$backendExt = Join-Path $OutputDir "backend.ext"
$backendP12 = Join-Path $OutputDir "backend.p12"
$truststore = Join-Path $OutputDir "backend-truststore.p12"
$clientKey = Join-Path $OutputDir "ds-client.key"
$clientCsr = Join-Path $OutputDir "ds-client.csr"
$clientCrt = Join-Path $OutputDir "ds-client.crt"
$clientExt = Join-Path $OutputDir "ds-client.ext"
$clientP12 = Join-Path $OutputDir "ds-client.p12"

openssl genrsa -out $caKey 4096
openssl req -x509 -new -nodes -key $caKey -sha256 -days 3650 -subj "/CN=UE Backend Dev CA" -out $caCert

@"
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:$BackendDns,IP:$BackendIp
"@ | Set-Content $backendExt -Encoding ASCII

openssl genrsa -out $backendKey 2048
openssl req -new -key $backendKey -subj "/CN=$BackendDns" -out $backendCsr
openssl x509 -req -in $backendCsr -CA $caCert -CAkey $caKey -CAcreateserial -out $backendCrt -days 825 -sha256 -extfile $backendExt
openssl pkcs12 -export -out $backendP12 -inkey $backendKey -in $backendCrt -certfile $caCert -name backend -passout "pass:$Password"

if (Test-Path $truststore) {
    Remove-Item $truststore -Force
}
keytool -importcert -noprompt -alias ue-backend-dev-ca -file $caCert -keystore $truststore -storetype PKCS12 -storepass $Password | Out-Null

@"
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=clientAuth
"@ | Set-Content $clientExt -Encoding ASCII

openssl genrsa -out $clientKey 2048
openssl req -new -key $clientKey -subj "/CN=ds-dev-smoke" -out $clientCsr
openssl x509 -req -in $clientCsr -CA $caCert -CAkey $caKey -CAcreateserial -out $clientCrt -days 825 -sha256 -extfile $clientExt
openssl pkcs12 -export -out $clientP12 -inkey $clientKey -in $clientCrt -certfile $caCert -name ds-client -passout "pass:$Password"

$fingerprintLine = & openssl x509 -in $clientCrt -noout -fingerprint -sha256
$fingerprint = ($fingerprintLine -replace '^.*=', '' -replace ':', '').ToLowerInvariant()

Write-Host "Generated dev mTLS material in $OutputDir" -ForegroundColor Green
Write-Host ""
Write-Host "Set environment variables:" -ForegroundColor Cyan
Write-Host "SERVER_MTLS_ENABLED=true"
Write-Host "SERVER_MTLS_PORT=9443"
Write-Host "SERVER_MTLS_KEY_STORE=file:$backendP12"
Write-Host "SERVER_MTLS_KEY_STORE_PASSWORD=$Password"
Write-Host "SERVER_MTLS_TRUST_STORE=file:$truststore"
Write-Host "SERVER_MTLS_TRUST_STORE_PASSWORD=$Password"
Write-Host "SERVER_MTLS_ALLOW_HEADER_FINGERPRINT_FALLBACK=false"
Write-Host ""
Write-Host "Update dev server identity fingerprint:" -ForegroundColor Cyan
Write-Host "UPDATE server_identities SET certificate_fingerprint = '$fingerprint' WHERE server_id = '$ServerId';"
Write-Host ""
Write-Host "curl example:" -ForegroundColor Cyan
Write-Host "curl -k --cert-type P12 --cert `"$clientP12`":$Password -H `"X-Server-Id: $ServerId`" https://localhost:9443/server/match-profile/build"
