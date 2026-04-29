# API Decisions

- OpenAPI 3.1 is the source for public, server, and admin contracts.
- Mutation APIs use Problem Details for errors.
- Versioned resources use precondition semantics.
- Non-idempotent POST operations require `Idempotency-Key`.
- `POST /server/runtime-preset-changes` requires `Idempotency-Key` to equal `operation_id`.
