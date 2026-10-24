# Concurrent Balance Service

Java 21 · Spring Boot 3 · Hibernate/JPA · PostgreSQL · Redis · Maven

## Run

```bash
docker compose up -d            # PostgreSQL 16 + Redis 7
mvn spring-boot:run             # REST API on :8080
mvn test                        # needs JDK 21, Maven and Docker (Testcontainers starts PostgreSQL and Redis)
mvn test -Dtest='BalanceServiceTest,BalanceServiceConcurrencyTest'   # only the Docker-free in-memory tests
```
Integration tests are skipped automatically when Docker is not available. No Maven wrapper is committed; run
`mvn wrapper:wrapper` once if you want `./mvnw test`.

Example: `POST /accounts {"accountId":"A","initialBalance":1000}`, `POST /accounts/A/credit {"amount":500,"transactionId":"TX-1"}`,
`POST /accounts/A/transfer {"destinationAccountId":"B","amount":300,"transactionId":"TX-2"}`, `GET /accounts/A/balance`.

## Architecture

```
BalanceService (interface)
 ├─ JpaBalanceService        PostgreSQL + Hibernate (authoritative) with Redis acceleration  <- used by the app
redis/  RedisDistributedLock · IdempotencyCache 
jpa/    AccountEntity · TransactionEntity · repositories
api/    BalanceController (optional REST layer; 400/404/409/422/503)
```

### Request flow (`JpaBalanceService`)
1. **Redis idempotency cache** – hit → replay the stored result, no DB work.
2. **Redis lock** per account (ids sorted) – queues contenders in Redis instead of on DB row locks.
3. **One DB transaction:**
   a. `INSERT INTO transactions ... ON CONFLICT (transaction_id) DO NOTHING` – claim the transactionId.
   b. `SELECT ... FOR UPDATE` on account rows, in sorted id order.
   c. Check, mutate (Hibernate dirty-checking flushes both rows), commit. On a business failure the failure is recorded and committed.
4. After commit: cache the result in Redis (TTL) and evict the balance cache.

**Rule of the design:** PostgreSQL decides, Redis helps. Steps 1, 2 and 4 can fail, expire or be disabled
without affecting correctness (`JpaConcurrencyDbOnlyTest` runs every concurrency scenario with the Redis lock off).

## Why PostgreSQL + Hibernate
- **Problem solved:** durability, multiple application instances sharing state, and an idempotency guarantee that survives restarts.
- **Concurrency:** `PESSIMISTIC_WRITE` (`FOR UPDATE`) row locks serialize operations per account; other accounts are not blocked.
  `CHECK (balance >= 0)` is a last-line guard for the "no negative balance" invariant.
- **Transfer atomicity:** both rows are updated in one DB transaction after all checks passed – commit or rollback together.
- **Deadlocks:** opposite transfers (A→B, B→A) could deadlock; row locks are always taken in `accountId` order, which removes circular waits.
  Remaining lock failures (e.g. lock timeout) are retried up to 3 times; the whole DB transaction, claim included, is rolled back first.
- **Trade-offs:** every write is a DB round trip and contention on hot accounts is bounded by row-lock throughput; tests need Docker.

## Idempotency
- `transactions.transaction_id` is the primary key. Claiming it with `ON CONFLICT DO NOTHING` returns 1 (owner) or 0 (duplicate).
  If another in-flight transaction holds the same id, PostgreSQL blocks until it commits (→ duplicate) or rolls back (→ we become the owner).
- Duplicates compare the stored **fingerprint** (type + accounts + amount). Same id with different parameters → `IdempotencyConflictException` (HTTP 409).
- Business failures (insufficient funds, overflow) are committed with the claim and replayed on retry. Validation errors and unexpected
  errors roll the claim back, so the id is not burned.
- **The claim happens before the row locks.** Otherwise a duplicate could hold an account lock while waiting for the owner's uncommitted
  claim, while the owner waits for that lock → deadlock.
- The DB keeps results permanently; Redis keeps them for `balance.idempotency.ttl` (24h) only as a fast path. Expired/lost Redis entries are
  harmless: the DB answers (tested by `idempotencyHoldsWhenRedisIsFlushed`).

## Why Redis, and what it does not do
- **Fast idempotency path (TTL):** retries of completed transactions are answered without touching PostgreSQL. It only stores results that are
  already committed and never claims "in progress" – a miss simply falls through to the DB.
- **Distributed lock:** `SET NX PX` with a random token and a compare-and-delete Lua release. A lease can expire (GC pause, failover), so
  two holders may overlap in rare cases – that is why it is an optimisation and the DB row lock stays authoritative. On timeout or Redis outage
  it fails **open** (request goes straight to the DB); `keepsWorkingWhenRedisIsDown` covers this.
- **Trade-offs:** one more component to run and monitor, extra network hops

## Other design decisions
- Same-account transfer is rejected with `InvalidRequestException`.
- Amounts are `long` minor units; overflow is detected with `Math.addExact` and treated as a business failure.
- Schema is explicit in `schema.sql` (not `ddl-auto`), so constraints are visible and reviewed. A real deployment would use Flyway/Liquibase.
- `open-in-view=false` so a request never shares a persistence context across transactions.
- Kafka is intentionally not used: it would make operations asynchronous (the interface is synchronous) and still needs idempotent consumers.

## Tests
- `BalanceServiceConcurrencyTest` – in-memory implementation, no Docker.
- `JpaBalanceServiceTest` – real PostgreSQL + Redis: basics, validation, idempotency (credit/debit/transfer), id reuse conflict,
  failure replay, Redis flushed, balance cache eviction, Redis down, state shared between two service instances.
- `JpaConcurrencyWithRedisLockTest` / `JpaConcurrencyDbOnlyTest` – same scenarios with and without the Redis lock: 20× race of two 700 debits,
  1,000 mixed concurrent operations (expected 105,000), concurrent duplicates, opposite transfers (deadlock check), random transfers over
  8 accounts (total conserved, never negative), 600 distinct transactions on one account. Each test uses unique account/transaction ids, so no cleanup is needed.

## Limitations / what I would do next
- `transactions` grows forever: add a retention job (for example, delete rows older than the retry window).
- Flyway migrations, metrics (lock wait time, cache hit ratio), and an outbox table if events must be published.
- Redlock or a fencing token if the Redis lock ever had to be relied on for correctness (the DB makes it unnecessary here).
