# PostgreSQL Backup And Restore

Production baseline uses PostgreSQL custom-format dumps for daily backups, WAL archiving for PITR readiness, and a restore drill against an isolated database.

## Backup

```powershell
powershell -ExecutionPolicy Bypass -File tools\backup\backup-postgres.ps1 -RetentionDays 14
```

The script writes `backups/postgres/ue_backend-<timestamp>.dump` and its adjacent
`*.dump.sha256` SHA-256 manifest, then removes local dumps and their manifests older
than the retention window. Copy both files together to external storage.

## Restore Drill

```powershell
powershell -ExecutionPolicy Bypass -File tools\backup\verify-postgres-backup.ps1 -BackupPath backups\postgres\ue_backend-YYYYMMDDTHHMMSSZ.dump
```

The verification script validates the mandatory SHA-256 manifest before restoring into a temporary database, checks Flyway history, and drops the temporary database.
Database, PostgreSQL user, and Docker Compose service parameters are validated as
identifiers before they are passed to Docker or SQL commands.

For a disposable end-to-end drill that removes its dump after verification, run:

```powershell
powershell -ExecutionPolicy Bypass -File tools\backup\run-backup-restore-drill.ps1
```

## Baseline Targets

- RPO: 24 hours for the MVP baseline.
- RTO: 30 minutes for a same-region restore from a validated dump.
- WAL/PITR baseline: local Docker Postgres starts with `wal_level=replica`, `archive_mode=on`, and archives WAL segments into the `postgres_wal_archive` volume.
- For production, schedule the backup script daily and copy both dumps and archived WAL segments to off-host storage.
- Before external release traffic, replace the local volume archive target with cloud/object storage or a managed PostgreSQL PITR feature.
