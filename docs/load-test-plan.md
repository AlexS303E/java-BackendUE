# Load Test Plan

Targets from v13:

- 100 CCU dev target.
- 500 CCU internal target.
- 2000 CCU stretch target.

Primary endpoints:

- `POST /auth/login`
- `GET /catalog/snapshot`
- `GET /me/access`
- `GET /me/presets`
- `PUT /me/presets/*`
- `POST /server/match-profile/build`
