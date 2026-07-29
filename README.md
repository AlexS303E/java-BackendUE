# Backend For UE

Backend skeleton for the UE5 multiplayer shooter inventory/loadout architecture.

## Scope

- Accounts and auth
- Catalog versions, items, mounts, and rules
- Access ledger and projection
- Weapon and outfit presets
- Authoritative match profile build
- Runtime preset changes from Dedicated Server
- Idempotency, outbox, admin audit, and server audit

## Local Run

Prerequisites:

- JDK 21
- Docker Desktop or another Docker-compatible runtime

```powershell
docker compose up -d
cd backend
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

The first implementation target should be the vertical flow:

1. Register player.
2. Bootstrap default access and presets.
3. Save weapon preset with revision precondition.
4. Build match profile for Dedicated Server.
5. Submit runtime preset change with idempotency.

## Current MVP Slice

Implemented now:

- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/logout`
- `GET /catalog/snapshot`
- `GET /me/access` with Bearer access token
- `GET /me/presets` with Bearer access token
- `PUT /me/presets/weapons/{classTag}/{presetSlot}` with Bearer access token and `If-Match`
- `POST /server/match-profile/build` with server identity headers
- `POST /server/runtime-preset-changes` with server identity headers and `Idempotency-Key == operation_id`

Dev Dedicated Server identity for local smoke tests:

- `X-Server-Id: 10000000-0000-0000-0000-000000000001`
- `X-Server-Certificate-Fingerprint: dev-ds-fingerprint`

Smoke check after `bootRun`:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\smoke\vertical-smoke.ps1

powershell -ExecutionPolicy Bypass -File tools\test\run-all-tests.ps1
```

Production admin access uses independently configured identities instead of a shared token. Configure them with indexed environment variables, for example `APP_ADMIN_IDENTITIES_0_ID`, `APP_ADMIN_IDENTITIES_0_TOKEN`, and `APP_ADMIN_IDENTITIES_0_ROLES`.

JWT key rotation is configured with `APP_AUTH_JWT_KEYS_0_ID`, `APP_AUTH_JWT_KEYS_0_PRIVATE_KEY`, `APP_AUTH_JWT_KEYS_0_PUBLIC_KEY` and `APP_AUTH_JWT_ACTIVE_KEY_ID`. Keep the previous public key in the list until every access token signed by it has expired.
