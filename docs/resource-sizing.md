# Production Resource Sizing

## Stage 1 backend envelope

The authoritative baseline is `config/production-resource-envelope.env`:

| Resource | Request | Limit |
|---|---:|---:|
| CPU | 2 vCPU | 4 vCPU |
| Memory | 1536 MiB | 2048 MiB |

The backend JVM uses container-aware percentage sizing:

```text
-XX:InitialRAMPercentage=25
-XX:MaxRAMPercentage=60
-XX:+UseG1GC
-XX:+ExitOnOutOfMemoryError
```

At the 2048 MiB limit this caps the Java heap near 1229 MiB and leaves roughly 819 MiB for metaspace, thread
stacks, direct buffers, TLS, and native libraries.

## Deployment preflight

Validate an effective deployment limit before rollout:

```powershell
powershell -ExecutionPolicy Bypass -File tools\deploy\validate-resource-envelope.ps1 `
  -CpuLimit 4 `
  -MemoryLimitMiB 2048 `
  -JavaToolOptions $env:JAVA_TOOL_OPTIONS
```

The production smoke starts the packaged JAR with the same `JAVA_TOOL_OPTIONS`.

## Re-sizing triggers

Re-run endpoint-isolation k6 profiles on the target host and review this envelope when:

- load increases beyond the 25 VU Stage 1 profile;
- CPU saturation exceeds 70% for five minutes;
- container memory working set exceeds 80%;
- GC pause p95 exceeds 100 ms or allocation pressure causes repeated old-generation collections;
- Hikari wait time or request p95 breaches its gate;
- the JVM exits due to OOM.

Increase replicas before raising per-instance limits when requests are horizontally distributable. Any new envelope
must record commit, host/container limits, JVM options, database topology, Redis topology, and k6 results.
