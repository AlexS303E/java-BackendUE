# Redis/cache MVP

This block adds Redis as a read-through optimization for high-read backend data while keeping PostgreSQL as the source of truth.

## Cached reads

- Catalog snapshot: `ue:catalog:snapshot:{realm_id}:{catalog_version}`.
- Player access projection: `ue:access:{player_id}:{catalog_version}:{access_revision}`.

Both cache families are versioned by the data dependency that makes the response valid. Catalog snapshots are keyed by `catalog_version`; player access is keyed by `access_revision`, so an access mutation naturally moves reads to a new key.

## Invalidation

- Catalog publish/rollback evicts the realm catalog snapshot index.
- Admin player cache invalidation evicts player access keys and still marks match profiles stale.
- Admin access mutation and projection rebuild evict player access keys as best effort.

Redis failures are ignored by the cache layer. The backend falls back to DB reads and writes the cache again when Redis becomes available.

## Config

- `APP_CACHE_ENABLED=true`
- `APP_CACHE_CATALOG_SNAPSHOT_TTL=PT10M`
- `APP_CACHE_ACCESS_TTL=PT5M`

Match profile build is intentionally not cached in this MVP because it persists profile snapshots, records audit state, and depends on request-level validation.
