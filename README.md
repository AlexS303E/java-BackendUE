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
.\gradlew.bat bootRun
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
- `GET /catalog/snapshot`
- `GET /me/access` with `X-Player-Id`
- `GET /me/presets` with `X-Player-Id`
- `PUT /me/presets/weapons/{classTag}/{presetSlot}` with `X-Player-Id` and `If-Match`
- `POST /server/match-profile/build`

Smoke check after `bootRun`:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\smoke\vertical-smoke.ps1
```
