# Production Deployment Shape

## Connector layout

Production uses three separate connector surfaces:

- Public HTTP connector: `server.port`, default `8080`. Serves player, catalog, auth, and admin HTTP routes according to the normal Spring Security rules.
- Management connector: `management.server.port`, default `8081`. Serves Actuator health, readiness, liveness, and info endpoints.
- Dedicated Server private mTLS connector: `app.server-auth.mtls.port`, default `9443`. Serves `/server/*` only and requires client certificates.

`server.port`, `management.server.port`, and `app.server-auth.mtls.port` must be distinct. The private mTLS connector must be reachable only from the internal Dedicated Server network or from an approved internal load balancer segment. It must not be exposed as a public internet listener.

## Private-port routing

Production must keep these settings enabled:

```dotenv
SERVER_MTLS_ENABLED=true
SERVER_MTLS_REQUIRE_PRIVATE_PORT=true
SERVER_MTLS_ALLOW_HEADER_FINGERPRINT_FALLBACK=false
```

When `SERVER_MTLS_REQUIRE_PRIVATE_PORT=true`, `/server/*` requests that arrive on the public connector are rejected before endpoint logic runs. Dedicated Servers must call `https://<backend-private-host>:<SERVER_MTLS_PORT>/server/...` and reuse TLS connections with the configured keep-alive limits.

## Ingress and proxy mTLS

Preferred deployment is TCP pass-through for the private mTLS listener, so the backend receives the client certificate directly from the TLS session and computes the SHA-256 fingerprint itself.

If an ingress, service mesh, or proxy terminates mTLS before the backend:

- The backend-facing hop must be private and mutually authenticated.
- The proxy must verify the Dedicated Server client certificate chain before forwarding.
- Forwarded identity headers such as `X-Forwarded-Client-Cert` are not an authentication source for the current backend implementation.
- `X-Server-Certificate-Fingerprint` remains disabled in production and must not be used to replace TLS client certificate authentication.
- The proxy rules must route only `/server/*` traffic to the private mTLS connector and must not expose the connector to general public traffic.

Any future proxy-terminated identity mode must be added as an explicit backend feature with a separate trust boundary, tests, and OpenAPI contract update.

## Secret management

Production keystore and truststore files are runtime secrets, not repository artifacts. Source them from a managed secret system such as Vault, cloud KMS-integrated secret storage, or Kubernetes Secret volumes.

Required runtime inputs:

```dotenv
SERVER_MTLS_KEY_STORE=<secret-mounted backend PKCS12/JKS path or URI>
SERVER_MTLS_KEY_STORE_PASSWORD=<secret value>
SERVER_MTLS_TRUST_STORE=<secret-mounted truststore path or URI>
SERVER_MTLS_TRUST_STORE_PASSWORD=<secret value>
```

Operational requirements:

- Do not bake private keys, PKCS12 files, JKS files, generated development certificates, or truststores into the application image or JAR.
- Do not use `tools/mtls/out/` or other generated development certificate directories in production.
- Rotate backend key material through the secret manager and restart or roll the backend nodes under the normal deployment controller.
- Rotate Dedicated Server client fingerprints through the `server_identity_certificates` table with active and retiring grace windows.
- Restrict secret read access to the backend runtime identity only.

## Release checks

Before promoting a production release:

- Run `tools/test/run-stage4-gate.ps1 -Mode Fast` for the local/CI fast gate.
- Run `tools/test/run-stage4-gate.ps1 -Mode Release -ListSteps` when reviewing the release gate plan without executing external smoke/load dependencies.
- Run `tools/test/run-stage4-gate.ps1 -Mode Release -SkipDocker` in a release environment.
- Confirm `/server/*` is unavailable on `server.port`.
- Confirm `/server/*` authenticates only through the private mTLS connector.
- Confirm Actuator health is available only on `management.server.port` according to the production exposure policy.

`Release` mode includes the fast gate, `bootJar`, production-profile smoke, mTLS smoke, and load smoke. Individual `-Skip*` switches are allowed only when the skipped check is run separately and recorded in release evidence.
