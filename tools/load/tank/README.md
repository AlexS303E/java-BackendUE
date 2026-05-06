# Yandex.Tank load testing layer

This directory contains the second-level load testing setup for the UE backend.

It complements the fast local k6/smoke checks with a self-hosted Yandex.Tank run in Docker.
The script generates Phantom request-style ammo with real JWTs and runs a mixed backend profile.

## What is tested

Default mixed profile:

- `GET /catalog/snapshot`
- `POST /auth/login`
- `GET /me/access`
- `GET /me/presets`
- `POST /server/match-profile/build` through the development header-fingerprint fallback

The script does not use real mTLS. Real mTLS transport is covered by `tools/mtls/run-mtls-smoke.ps1`.
This load layer focuses on throughput, latency, DB/Redis connection pools, and backend stability.

## Prerequisites

- Docker Desktop / Docker Engine
- JDK 21 available as `java`
- Gradle wrapper in `backend/`
- Network access for pulling `yandex/yandex-tank:latest` on the first run

## Run

From repository root:

```powershell
powershell -ExecutionPolicy Bypass -File tools\load\tank\run-yandex-tank.ps1
```

A slightly stronger run:

```powershell
powershell -ExecutionPolicy Bypass -File tools\load\tank\run-yandex-tank.ps1 -Players 50 -Schedule "line(10, 100, 5m) const(100, 5m)"
```

Read/player API only, without server API:

```powershell
powershell -ExecutionPolicy Bypass -File tools\load\tank\run-yandex-tank.ps1 -NoServerApi
```

Use an already running backend:

```powershell
powershell -ExecutionPolicy Bypass -File tools\load\tank\run-yandex-tank.ps1 -SkipBackendStart
```

## Outputs

Generated files are ignored by Git:

- `tools/load/tank/generated/load.yaml`
- `tools/load/tank/generated/mixed.ammo`
- `tools/load/tank/results/tank.log`
- `tools/load/tank/results/phout.log`
- `tools/load/tank/results/phout-summary.csv`
- `tools/load/tank/results/backend-logs/*.log`

## Notes

On Windows Docker Desktop the Tank container uses `host.docker.internal:8080` as the target.
On Linux the script uses `--network host` and targets `127.0.0.1:8080`.
