# 3. Redis Streams for the job queue, with a relay for reliability

**Status:** accepted (Phase 1)

## Context

Webhook handling must be fast and must not lose failed runs. GitHub does **not**
automatically redeliver a webhook if we fail to handle it. Workers can crash halfway.

## Decision

- The webhook transaction stores the run as `RECEIVED`. Only **after it commits** is the
  run id added to a Redis Stream (`cifa:runs:ingest`) and the run marked `QUEUED`.
- If publishing fails, the run stays `RECEIVED`, and a scheduled **relay** in
  api-service re-queues runs left `RECEIVED` for more than 30 seconds. This is a simple
  form of the transactional outbox pattern.
- Workers join a consumer group and acknowledge an entry only after handling it.
  Entries left unacknowledged by a crashed worker are reclaimed after 2 minutes.
- A temporary failure (GitHub 5xx or rate limit) sets the run back to `RECEIVED` and
  acknowledges the entry, so the relay retries it after a delay. After 3 attempts, or on
  a permanent error, the run becomes `INGEST_FAILED` with the reason.
- Every step is idempotent (unique delivery ids, unique run attempts, upserted jobs), so
  handling a run twice is harmless.

## Why Redis Streams

Redis is light to run locally, and Streams provide consumer groups, acknowledgements
and pending-entry recovery. Kafka would add a lot to run for this volume. Phase 9 swaps
in AWS SQS behind the same producer/consumer code.

## Consequences

- At-least-once delivery, so handlers must stay idempotent.
- If Redis loses its data, runs stuck in `QUEUED` are not picked up again automatically
  yet. The relay could also cover old `QUEUED` runs if this becomes a problem.
