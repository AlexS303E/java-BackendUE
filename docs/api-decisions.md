# API Decisions

- OpenAPI 3.1 is the source for public, server, and admin contracts.
- Mutation APIs use Problem Details for errors.
- Versioned resources use precondition semantics.
- Non-idempotent POST operations require `Idempotency-Key`.
- `POST /server/runtime-preset-changes` requires `Idempotency-Key` to equal `operation_id`.
- `POST /server/runtime-events` maps `Idempotency-Key` through `api_idempotency_records`; the key does not need to equal `event_id`. Same key and same body replays the stored `recorded` response with `duplicate=true`; same key with a different body returns `IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST`.
