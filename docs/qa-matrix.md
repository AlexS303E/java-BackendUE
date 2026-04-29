# QA Matrix

Initial vertical flow:

1. Register player.
2. Bootstrap default access and presets.
3. Save weapon preset with `If-Match`.
4. Build match profile for supported catalog version.
5. Submit runtime preset change with `Idempotency-Key` equal to `operation_id`.
6. Verify revision conflict creates pending change.
