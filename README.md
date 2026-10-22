# Idempotent Background Transaction Processing Service

A compact Java 17 implementation of the L2 assessment. It processes a batch of transaction events, protects against replays, applies per-account sequence ordering, retries simulated transient faults, isolates malformed records, and reports outcomes.

## Run

Requirements: JDK 17+ and Maven 3.9+.

```sh
mvn test
mvn exec:java
```

No external service or database is needed. Maven downloads JUnit and build plugins on the first run.

## Sample behavior

The input in `Main` mirrors the supplied scenario: sequence 2 arrives before 1, the same business transaction is replayed, a negative amount is rejected, and one record experiences one transient error before success. The first TXN-2002 result is `PENDING_ORDER`; after TXN-2001 arrives, the processor drains sequence 2. Its later replay is `DUPLICATE`. TXN-2005 succeeds on attempt 2. ACC-201 ends at 85 and ACC-203 at 500.

The printed per-request result is the status at the time that request was received. The summary reports current status by request ID, so the earlier pending event is counted as processed after its gap is filled.

## Design note

### Architecture

`Main` provides a replaceable in-memory source. `TransactionProcessor` owns validation, ordering, state, retry, and balance effects. `TransactionRequest` and `ProcessingResult` are immutable records. This keeps the business behavior independent of input parsing.

### Idempotency

A transaction ID is the business idempotency key; a request ID catches reuse of the same delivery/request. A transaction ID can only update the balance once. A repeat returns `DUPLICATE`. All in-memory mutation is synchronized to prevent concurrent calls in this single JVM from interleaving the check and effect.

### Ordering

Sequence numbers are monotonic per account and begin at 1. Future sequences are held in a per-account ordered map. On receipt of the missing sequence, the processor drains any now-contiguous pending events. Older sequence deliveries are treated as stale/duplicate. A terminal failed event advances the sequence in this demo so one poison event does not block later work; a real ledger may instead require an explicit gap-repair or operator decision.

### Retry and failure

`transientFailuresBeforeSuccess` is deterministic fault injection for the assessment. The processor retries up to a configured maximum of three attempts. A successful attempt mutates the balance once, after the simulated error boundary. Exhaustion sets `FAILED` and leaves the balance unchanged. Invalid rows are returned as failed outcomes without stopping the caller's batch.

### State and observability

The processor tracks received, processing, retry-pending, pending-order, processed, duplicate, and failed states. Java logging emits transaction ID, status, and attempt count, avoiding amount and account details. `summary()` returns counts by current request status; `results()` exposes outcomes for a report.

### Assumptions and production hardening

- This demo uses one currency-agnostic balance per account. A production ledger should key balances by account and currency, enforce overdraft/business rules, and use an immutable ledger entry as the source of truth.
- All stores are in memory: restart loses deduplication state, balances, sequence cursors, and pending work. Replace them with durable storage and enforce a unique constraint on the business idempotency key.
- In a multi-instance deployment, `synchronized` is insufficient. Use atomic database transactions/locking or partition queue messages by account, and make the ledger write plus idempotency record one atomic unit.
- Retry scheduling is immediate here. Production should classify retryable errors, use backoff and jitter, set timeouts, cap attempts, and route exhausted records to a dead-letter/reconciliation flow.
- Pending sequence gaps have no timeout or durable recovery. Production needs gap alerts, replay/reconciliation and an operator workflow.
- Add structured JSON logs, metrics (throughput, duplicate rate, retries, failures, oldest pending age), tracing, health checks, access control, retention policies, and redaction appropriate to the deployment.
- `REVERSAL` is treated as a positive credit only for demonstration; real reversal semantics must reference and compensate a prior ledger entry.

### AI-assisted work

If AI tools were used to prepare or refine the submission, disclose which tools and what they helped with. Review and run the generated code yourself, validate the edge cases with tests, and be prepared to explain every design choice. Edit this section to reflect your actual usage; do not claim tools or validation you did not perform.
