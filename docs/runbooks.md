# Operational runbooks

These runbooks cover the minimum production incident set for Stage 1 readiness.
Each incident should end with a short timeline, customer impact summary, and
follow-up action list.

## DB down

### Symptoms

- Readiness check reports database connectivity failure.
- API error rate increases for endpoints that require PostgreSQL.
- PostgreSQL container, process, or managed instance reports unavailable.

### Immediate action

- Stop non-critical workers if they amplify retries, starting with outbox publisher.
- Confirm whether the failure is network, storage, credentials, or instance health.
- Fail closed for write endpoints and keep liveness separate from readiness.
- If primary storage is unrecoverable, start the documented restore flow from
  `docs/backup-restore.md`.

### Verification

- Readiness returns healthy after database connectivity is restored.
- Flyway history is readable and all expected migrations are successful.
- Core smoke tests pass for auth, catalog snapshot, access, presets, and match profile.

### Follow-up

- Attach database logs, application errors, and recovery timestamps to the incident.
- Review connection pool timeout and retry settings.
- Schedule or update a restore drill if backup restore was involved.

## Redis down

### Symptoms

- Cache health reports Redis unavailable.
- Cache-backed read endpoints show increased PostgreSQL load or latency.
- Logs contain Redis connection timeout or command timeout errors.

### Immediate action

- Keep route-specific conservative fallback enabled only for reads that are safe to serve
  from PostgreSQL.
- Watch database CPU, query latency, and connection pool saturation.
- Reduce external polling traffic if fallback starts pressuring PostgreSQL.
- Restore Redis or fail traffic over to the approved replacement instance.

### Verification

- Cache health returns healthy and Redis command latency is within normal range.
- Database load returns to the pre-incident baseline.
- Fallback counters stop increasing for cache-backed routes.

### Follow-up

- Review whether fallback limits were sufficient.
- Keep the Redis degradation test results with the incident record.
- Tune route-specific fallback or rate limits if PostgreSQL load exceeded budget.

## Outbox stuck

### Symptoms

- Outbox lag gauge grows continuously.
- Pending outbox rows stop transitioning to completed.
- Dead-letter or retry counters increase for the same event type.
- `outbox.circuit_breaker.open` becomes `1` or `outbox.circuit_breaker.opened`
  increases.

### Immediate action

- Confirm publisher process health and downstream side-effect availability.
- Pause or slow publishers if retry traffic is amplifying the downstream failure.
- Check retry backoff, batch size, circuit breaker state, and dead-letter volume.
- Check `outbox.pending.lag.seconds`, `outbox.events{status="dead_letter"}`,
  `outbox.circuit_breaker.open`, and `outbox.circuit_breaker.opened`.
- Tune `OUTBOX_CIRCUIT_BREAKER_FAILURE_THRESHOLD` or
  `OUTBOX_CIRCUIT_BREAKER_COOLDOWN_SECONDS` only with an incident owner and rollback note.
- Manually inspect the oldest pending rows before replaying anything.

### Verification

- Outbox lag decreases and old rows are processed in order.
- Side effects are idempotent and no duplicate destructive action was produced.
- Dead-letter rows have a documented owner and resolution path.

### Follow-up

- Add a regression test or metric alert for the failed event type.
- Record replay decisions with row ids, operation ids, and timestamps.
- Review retry policy if the incident created a retry wave.

## Catalog rollback

### Symptoms

- Match profile build or loadout validation fails after catalog activation.
- Dedicated servers report unsupported catalog version.
- Active matches depend on a catalog version that is being rolled back.

### Immediate action

- Freeze new catalog activation and block new matches for the bad version.
- Keep existing matches on versions they already accepted when safe.
- Use separate `allow_new_matches` and `allow_existing_matches` decisions.
- Confirm dedicated server supported versions before changing active routing.

### Verification

- New match assignment avoids the rolled-back version.
- Existing matches continue only on catalog versions their server supports.
- Contract smoke tests pass for catalog snapshot and match profile build.

### Follow-up

- Document the bad catalog version, replacement version, and affected match ids.
- Add a catalog lifecycle regression test for the failed rule.
- Update DS compatibility notes if the rollback exposed a version gap.

## DS revoke

### Symptoms

- A dedicated server identity is compromised, stale, or behaving outside scope.
- Server-auth audit logs show denied or suspicious `/server/*` access.
- Match assignment includes a server that should no longer receive traffic.

### Immediate action

- Revoke or disable the server identity in the server identity store.
- Remove the server from match assignment and drain active sessions if required.
- Confirm mTLS/server identity enforcement is active for production `/server/*`.
- Rotate credentials if compromise is suspected.

### Verification

- Requests from the revoked identity are denied.
- Match assignment no longer returns the revoked server.
- Audit events show the revoke action and subsequent denied attempts.

### Follow-up

- Attach audit rows, identity id, certificate fingerprint, and revoke reason.
- Review allowed scopes for the identity group.
- Add a security regression case if revoke behavior was incomplete.

## Overload

### Symptoms

- p95 latency exceeds SLO or connection pools saturate.
- `backend.rate_limit.rejections`, timeout, or circuit-breaker metrics rise sharply.
- Dashboard polling or fallback reads increase load on the game API database.

### Immediate action

- Protect gameplay endpoints first by reducing dashboard polling and non-critical reads.
- Confirm whether overload is CPU, database, Redis, worker, or downstream related.
- Check `backend.rate_limit.rejections` by bucket before changing traffic policy.
- Apply route-specific rate limits and keep write timeouts conservative.
- Scale the bottleneck only after verifying it will not amplify retries.

### Verification

- p95 latency returns under the endpoint SLO.
- Error rate and saturation metrics return to normal.
- Load smoke tests pass for the protected critical routes.

### Follow-up

- Record the bottleneck, traffic source, and mitigation timeline.
- Add or adjust alerts for the earliest reliable saturation signal.
- Update load-test scenarios if the incident used a new traffic pattern.
