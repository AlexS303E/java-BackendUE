# OpenAPI contract test matrix

## Public API

| Endpoint | Check |
|---|---|
| `POST /auth/register` | successful register, duplicate login, invalid payload |
| `POST /auth/login` | successful login, wrong password, banned/disabled account |
| `POST /auth/refresh` | valid refresh, revoked refresh, expired refresh |
| `POST /auth/logout` | valid logout, repeated logout |
| `GET /catalog/snapshot` | returns body, returns `ETag`, unsupported catalog version |
| `GET /me/access` | requires auth, returns access revision/items |
| `GET /me/presets` | requires auth, returns weapon/outfit presets |
| `PUT /me/presets/weapons/{class_tag}/{preset_slot}` | requires `If-Match`, stale revision returns `412`, invalid loadout returns `422`, success returns new `ETag` |
| `GET /me/notifications` | requires auth, returns notifications list |
| `POST /me/notifications/{notification_id}/read` | marks notification as read, unknown notification |
| `GET /me/post-match-pending-changes` | returns pending changes |
| `POST /me/post-match-pending-changes/{change_id}/resolve` | apply, discard, manual merge not implemented |

## Server API

| Endpoint | Check |
|---|---|
| `POST /server/match-profile/build` | requires server headers, unsupported catalog returns `409 CATALOG_VERSION_NOT_SUPPORTED`, success returns match profile |
| `POST /server/runtime-preset-changes` | requires server headers, requires `Idempotency-Key`, mismatched key returns `400 IDEMPOTENCY_OPERATION_ID_MISMATCH`, stale revision returns `409 PRESET_REVISION_CONFLICT`, duplicate operation is idempotent |
| `POST /server/runtime-events` | requires server headers, requires `Idempotency-Key`, duplicate key/body returns duplicate response, duplicate key/different body returns `409 IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST`, accepts known runtime event types |

## Admin API

| Endpoint | Check |
|---|---|
| `GET /admin/status/overview` | requires `X-Admin-Token` |
| `GET /admin/status/servers` | requires `X-Admin-Token` |
| `GET /admin/status/matches` | requires `X-Admin-Token` |
| `GET /admin/status/recent-audit` | requires `X-Admin-Token` |
| `GET /admin/status/players/search` | requires `X-Admin-Token`, supports query filter |
| `GET /admin/status/players/{player_id}/weapon-access` | requires `X-Admin-Token`, unknown player |
| `GET /admin/status/players/{player_id}/weapon-access/audit` | requires `X-Admin-Token`, returns audit events |
| `POST /admin/players/{player_id}/access/items/{item_id}` | requires `X-Admin-Token`, `X-Admin-Confirm`, `Idempotency-Key`; duplicate idempotency key returns same result or conflict for different request |
| `POST /admin/items/hide` | requires `X-Admin-Token`, `X-Admin-Confirm`, `Idempotency-Key`, reason/comment audit context |
| `POST /admin/items/reveal` | requires `X-Admin-Token`, `X-Admin-Confirm`, `Idempotency-Key`, reason/comment audit context |
| `POST /admin/items/shop-lock` | requires `X-Admin-Token`, `X-Admin-Confirm`, `Idempotency-Key`, reason/comment audit context |
| `POST /admin/items/shop-unlock` | requires `X-Admin-Token`, `X-Admin-Confirm`, `Idempotency-Key`, reason/comment audit context |
| `POST /admin/items/quest-lock` | requires `X-Admin-Token`, `X-Admin-Confirm`, `Idempotency-Key`, reason/comment audit context |
| `POST /admin/items/quest-unlock` | requires `X-Admin-Token`, `X-Admin-Confirm`, `Idempotency-Key`, reason/comment audit context |
| `POST /admin/items/disable` | requires `X-Admin-Token`, `X-Admin-Confirm`, `Idempotency-Key`, reason/comment audit context |
| `POST /admin/items/enable` | requires `X-Admin-Token`, `X-Admin-Confirm`, `Idempotency-Key`, reason/comment audit context |
| `POST /admin/access/rebuild-projection` | requires `X-Admin-Token`, `X-Admin-Confirm`, `Idempotency-Key`; rebuilds from entitlement ledger |
| `POST /admin/cache/invalidate-player` | requires `X-Admin-Token`, `X-Admin-Confirm`, `Idempotency-Key`; records cache invalidation |
| `POST /admin/server-identities/revoke` | requires `X-Admin-Token`, `X-Admin-Confirm`, `Idempotency-Key`; revokes server identity |
| `POST /admin/catalog/publish` | requires `X-Admin-Token`, `X-Admin-Confirm`; validates rollout and catalog lifecycle constraints |
| `POST /admin/catalog/rollback` | requires `X-Admin-Token`, `X-Admin-Confirm`; validates rollback target and lifecycle constraints |
| `POST /admin/control/players/{player_id}/invalidate-cache` | requires `X-Admin-Token`, `X-Admin-Confirm` |
| `POST /admin/control/server-identities/{server_id}/revoke` | requires `X-Admin-Token`, `X-Admin-Confirm` |
| `POST /admin/control/outbox/retry-failed` | requires `X-Admin-Token`, `X-Admin-Confirm` |
| `POST /admin/control/players/{player_id}/weapon-access` | requires `X-Admin-Token`, `X-Admin-Confirm` |

## Error model

Every endpoint must return errors in `ProblemDetails` format.

Minimum checked codes:

- `400 VALIDATION_ERROR`
- `401 UNAUTHENTICATED`
- `403 FORBIDDEN`
- `404 NOT_FOUND`
- `409 PRESET_REVISION_CONFLICT`
- `409 CATALOG_VERSION_NOT_SUPPORTED`
- `409 IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST`
- `412 PRECONDITION_FAILED`
- `422 LOADOUT_VALIDATION_FAILED`
- `428 PRECONDITION_REQUIRED`
- `503 SERVICE_UNAVAILABLE`
