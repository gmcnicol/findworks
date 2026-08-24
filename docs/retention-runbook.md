# Retention and deletion runbook

Run these commands only with the pilot Investigator's approval and production database credentials. Each mutation records a content-free audit event. Keep titles, answers, email addresses, tokens, and notes out of command arguments and logs.

Use the built application with web startup disabled:

```sh
java -jar target/findworks-0.0.1-SNAPSHOT.jar \
  --spring.main.web-application-type=none \
  --findworks.retention.command=extend \
  --findworks.retention.discovery-id=DISCOVERY_UUID \
  --findworks.retention.until=2030-12-01T00:00:00Z
```

Supported commands are `extend`, `delete-discovery`, `delete-session`, `schedule`, `send-warning`, `purge`, and `prune-audit`. Session deletion also needs `--findworks.retention.mission-id` and `--findworks.retention.session-id`. Discovery deletion needs `--findworks.retention.discovery-id`.

Deletion blocks access immediately. Run `purge` until no work remains, within the recorded seven-day deadline. A failed or interrupted purge remains leased for five minutes and is then claimable again. Verify `deletion_ledger.stage = 'completed'`; `backup_expiry_due_at` is metadata for the restore procedure in issue #34 and is not proof that provider backups have expired.
