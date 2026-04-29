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
