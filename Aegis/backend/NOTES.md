# Opencode — Local Notes

## Upstream bug — `UNIQUE(event.aggregate_id, event.seq)` (opencode 1.18.31)

- **Symptom:** `EffectDrizzleQueryError: Failed query: insert into "event" (id, aggregate_id, seq, type, data) ... UNIQUE constraint failed: event.aggregate_id, event.seq`.
- **Reproduction:** concurrent activity on the same `aggregate_id` (session) triggers a race where two writers compute the same next `seq` and both try to `INSERT` with that seq. The second fails. Example log line (from `/root/.local/share/opencode/log/opencode.log`):

  ```
  2026-09-14T08:02:07.438Z ERROR ... Failed query: insert into "event" ... params: evt_09ef00d9e...,ses_faec51e98ffeS3v7HJnrT1rlvw,2761,session.updated.1,...
  cause: UNIQUE constraint failed: event.aggregate_id, event.seq
  ```

  Also observed on opencode 1.18.25→1.18.31 for `ses_fae7f2a7...` at `seq 4376`, and multiple `session.updated`/`session.deleted` retries at the same seq. Isolated second family: `FOREIGN KEY constraint failed` on `message`/`part` inserts for missing `session_id`/`message_id` (2026-09-17).

- **Corruption path:** the constraint failures themselves do not corrupt the DB, but retry loops can leave WAL in an inconsistent state. In this install a manual `python3 sqlite3 UPDATE/DELETE ... PRAGMA wal_checkpoint(TRUNCATE)` was executed *while `opencode serve` was still running* (window 2026-09-18T05:25 WAL 4.1 MB → 2026-09-18T05:37 WAL 4.1 KB, evidenced by `opencode.db.bak-before-prune*` and `opencode.db.CORRUPTED-20260918`). That shrank/truncated the live WAL and produced a malformed image. Recovery was by restoring a `bak-before-prune` copy and restarting the server. **No live `DELETE`/`UPDATE` against `opencode.db` while the TUI/serve holds it open; if a repair is needed, stop all opencode processes first, work on a copy, then swap it in.**

- **Current DB health (2026-09-18, post-recovery):** `PRAGMA integrity_check=ok`, 19 sessions via `GET /session`, version `1.18.31`. The stuck companion session `ses_f59ee1524ffe6C75XZej2OGr92` (1392 msgs / 21353 events / 21 MB) is abandoned read-only (export at `/tmp/opencode/ses_f59ee1524ffe6C75XZej2OGr92.json`); continuation is `ses_f4c94c66dffenoqFOCFaytnre7` seeded from `SESSION_HANDOFF.md`.

- **Non-destructive policy:** do not prune or manually delete rows from `opencode.db` live; do not `VACUUM`/`wal_checkpoint` under a running serve. For per-session bloat, export read-only to JSON, abandon the session, and continue in a new session via `POST /session?directory=/sdcard/projects` (see `SESSION_HANDOFF.md`). Check upstream changelog/issues for `database disk image is malformed` / `EffectDrizzleQueryError` before any schema fix.

- **Filing:** no in-TUI feedback channel was used. This note is the local record; upstream report (GitHub `opencode` issues) should attach: the reproduction above, the version `1.18.31`, and the log excerpt around `event.aggregate_id, event.seq` (full context available in `/root/.local/share/opencode/log/opencode.log`).

## Companion project — `SESSION_HANDOFF.md`

- Continuation handoff for the Companion web/hub work lives at `/sdcard/projects/Aegis/backend/SESSION_HANDOFF.md`. The new session must read it first and confirm readiness there before resuming UI fixes.
