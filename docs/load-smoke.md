# Load Smoke

`tools/load/run-load-smoke.ps1` runs a small k6 scenario against a locally running backend.

Covered measured endpoints:

- `POST /auth/login`
- `GET /catalog/snapshot`
- `GET /me/access`
- `GET /me/presets`
- `PUT /me/presets/weapons/{class_tag}/{preset_slot}`
- `POST /server/match-profile/build`
- `POST /server/runtime-preset-changes`

The k6 `setup()` phase registers temporary players first. Registration is outside the measured gameplay path.

Example:

```powershell
powershell -ExecutionPolicy Bypass -File tools\load\run-load-smoke.ps1 -BaseUrl http://localhost:8080 -Vus 5 -Duration 30s
```

The local backend must allow the dev server header fallback, or the server endpoints must be reachable through an equivalent test identity:

- `SERVER_ID=10000000-0000-0000-0000-000000000001`
- `SERVER_FINGERPRINT=dev-ds-fingerprint`
- `SERVER_BUILD_ID=ds-dev-smoke`

The script expects `k6` in `PATH` and does not start Docker or the backend.
