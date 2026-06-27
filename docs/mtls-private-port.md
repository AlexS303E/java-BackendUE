# Dedicated Server private mTLS port

## Intent

The public backend connector stays on `server.port` and serves `/auth`, `/catalog`, `/me`, and `/admin`.
Production health endpoints use the separate `management.server.port`.
Dedicated Server endpoints under `/server/*` use a separate private HTTPS connector with mandatory client certificate authentication.

## Runtime model

- Public connector: `server.port`, default `8080`.
- Management connector: `management.server.port`, production default `8081`.
- Private mTLS connector: `app.server-auth.mtls.port`, default `9443`.
- Private connector TLS mode: `client-auth=need`.
- `/server/*` requests are rejected unless they arrive on the private mTLS port when `app.server-auth.mtls.enabled=true`.
- Backend computes the SHA-256 fingerprint from the TLS client certificate and compares it with the current `server_identities.certificate_fingerprint` plus usable rows in `server_identity_certificates`.
- `X-Server-Id` remains required to select the expected server identity.
- `X-Server-Certificate-Fingerprint` is dev-only fallback and is ignored when mTLS is enabled.
- Production deployment rules for internal routing, ingress/proxy mTLS termination, and secret-backed keystore/truststore handling are tracked in `docs/production-deployment.md`.

## Configuration

`application.yml` maps the local `SERVER_MTLS_*` environment variables to `app.server-auth.mtls.*`.

```dotenv
SERVER_MTLS_ENABLED=true
SERVER_MTLS_PORT=9443
SERVER_MTLS_REQUIRE_PRIVATE_PORT=true
SERVER_MTLS_ALLOW_HEADER_FINGERPRINT_FALLBACK=false
SERVER_MTLS_KEY_STORE=file:tools/mtls/out/backend.p12
SERVER_MTLS_KEY_STORE_PASSWORD=changeit
SERVER_MTLS_KEY_STORE_TYPE=PKCS12
SERVER_MTLS_KEY_ALIAS=backend
SERVER_MTLS_TRUST_STORE=file:tools/mtls/out/backend-truststore.p12
SERVER_MTLS_TRUST_STORE_PASSWORD=changeit
SERVER_MTLS_TRUST_STORE_TYPE=PKCS12
SERVER_MTLS_CONNECTION_TIMEOUT=5s
SERVER_MTLS_KEEP_ALIVE_TIMEOUT=30s
SERVER_MTLS_MAX_KEEP_ALIVE_REQUESTS=100
```

## Dev certificates

Generate local certs:

```powershell
powershell -ExecutionPolicy Bypass -File tools/mtls/generate-dev-certs.ps1
```

The default output directory is `tools/mtls/out/` relative to the repository root, even when the script is launched from another current directory.
Generated certificate material is intentionally ignored by git.

The script prints an SQL update for `server_identities.certificate_fingerprint`.
Apply it to the dev database before testing the private mTLS connector.

## Local test curl shape

`/server/*` requests should go to `https://localhost:9443`, not `http://localhost:8080`.

```powershell
curl.exe -k `
  --cert-type P12 `
  --cert "tools/mtls/out/ds-client.p12:changeit" `
  -H "X-Server-Id: 10000000-0000-0000-0000-000000000001" `
  -H "Content-Type: application/json" `
  -d "{}" `
  https://localhost:9443/server/match-profile/build
```

The request body above is intentionally incomplete; it should fail validation after mTLS/auth succeeds.

## Timeouts and keep-alive

The private connector uses `app.server-auth.mtls.connection-timeout`, default
`5s`, to bound private-port connection establishment and TLS handshakes.
It also sets `app.server-auth.mtls.keep-alive-timeout`, default `30s`, and
`app.server-auth.mtls.max-keep-alive-requests`, default `100`, so Dedicated
Server clients can reuse TLS connections instead of paying a handshake on every
`/server/*` request.
