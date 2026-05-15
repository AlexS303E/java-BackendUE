# PostgreSQL Backup And Restore

Production baseline uses PostgreSQL custom-format dumps for daily backups and a restore drill against an isolated database.

## Backup

```powershell
powershell -ExecutionPolicy Bypass -File tools\backup\backup-postgres.ps1 -RetentionDays 14
```

The script writes `backups/postgres/ue_backend-<timestamp>.dump`, then removes local dumps older than the retention window.

## Restore Drill

```powershell
powershell -ExecutionPolicy Bypass -File tools\backup\verify-postgres-backup.ps1 -BackupPath backups\postgres\ue_backend-YYYYMMDDTHHMMSSZ.dump
```

The verification script restores into a temporary database, checks Flyway history, and drops the temporary database.

## Baseline Targets

- RPO: 24 hours for the MVP baseline.
- RTO: 30 minutes for a same-region restore from a validated dump.
- For production, schedule the backup script daily and keep off-host copies. Add WAL archiving/PITR before external release traffic.
