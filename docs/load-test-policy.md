# Load Test Policy

## Stage 1 release gate

k6 is the authoritative Stage 1 performance gate. Endpoint isolation scenarios use the shared thresholds in
`tools/load/k6/performance-gates.js`; a threshold failure makes the run fail.

Required profile:

- 25 virtual users.
- 3 minute duration.
- Endpoint isolation runs for health, catalog, auth, access, presets, match-profile, and runtime changes.
- Mixed smoke run after isolation runs.
- Results must identify the backend commit, host specification, database state, and test date.

The match-profile release target is p95 below 300 ms. A result above that target is evidence for optimization,
not a passing baseline.

## Yandex.Tank status

Yandex.Tank is diagnostic only. Its Docker Desktop run crosses the container-to-host networking boundary and the
recorded result included 4.27% connection timeouts. That result must not be used as release evidence or compared
directly with native k6 latency.

The Tank scripts remain available for RPS shaping experiments. Promoting Tank back to a release gate requires:

- execution from a dedicated load-generator host;
- a target deployment on representative hardware;
- pinned Tank image and configuration;
- no Docker Desktop host bridge;
- the same endpoint SLOs as the authoritative k6 profile.
