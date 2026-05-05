# Catalog lifecycle MVP

## Admin operations

- `POST /admin/catalog/publish` publishes a `validated`, `canary`, or `previous` catalog version as the only active catalog for new matches in the realm.
- `POST /admin/catalog/rollback` moves the realm pointer back to a previous deployment or to an explicit rollback target. It does not create a new catalog version.

Both operations migrate the current durable player access and preset rows to the target catalog version. Already generated match profiles remain stored as pinned JSON snapshots, but are marked stale so new admission/build flows use the active deployment.

## Version behavior

- A realm has at most one `catalog_deployments` row with `deployment_state = active` and `allow_new_matches = true`.
- New match profile builds negotiate only catalog versions that the Dedicated Server supports and that are active for new matches in the requested realm.
- Existing match profile snapshots keep their original `catalog_version` in `player_match_profiles.payload`.

## Sanitation and ID migration

During publish/rollback, durable presets are rebuilt against the target catalog:

- If an item, mount, or slot is still valid, it is preserved.
- If `catalog_id_migration_map.migration_action = map`, the old ID is replaced with `new_id`.
- If `migration_action = drop`, only the affected invalid part is cleared.
- If `migration_action = manual`, the affected part is cleared and a `post_match_pending_changes` row is created with `reason_code = catalog_conflict` and payload source `catalog_lifecycle`.

The MVP intentionally keeps manual conflicts in the existing pending-change queue instead of introducing a separate catalog remediation table.
