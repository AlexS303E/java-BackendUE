# QA Matrix

Initial vertical flow:

1. Register player.
2. Bootstrap default access and presets.
3. Save weapon preset with `If-Match`.
4. Build match profile for supported catalog version.
5. Submit runtime preset change with `Idempotency-Key` equal to `operation_id`.
6. Verify revision conflict creates pending change.

Security negative flow:

1. Reject server calls with missing or invalid server identity headers.
2. Reject revoked and expired server identities.
3. Reject insufficient server scopes, wrong realm, wrong server build, and wrong match owner.
4. Reject admin endpoints without a valid `X-Admin-Token`.
