# Repository Composition

`BalanceService` only depends on `CrudRepository`. The composition root binds the logical `game` role to a named `local`, `mongo`, `postgresql` or `redis` source. Entity mapping, codec, optimistic versions and business code stay the same.

Run the local path from the repository root with Java 21:

```powershell
mvn -B -ntp -q -DskipTests install
mvn -B -ntp -q -f examples/repository-composition/pom.xml clean test exec:java
```

Expected: `repository-composition=ok|backend=local|amount=15|version=2`.

Tests exercise the existing local envelope representations for all four backends, runtime ownership and creation rollback. These representation tests do not connect to real databases. This comparison example intentionally declares all three adapter integrations; production applications should declare only their selected integrations.

To run against an isolated external service, supply the existing adapter environment variables and select a backend explicitly:

| Backend | Required environment variables |
| --- | --- |
| `mongo` | `ZERO_MONGO_URI`, `ZERO_MONGO_DATABASE` |
| `postgresql` | `ZERO_POSTGRESQL_URL`, `ZERO_POSTGRES_USER`, `ZERO_POSTGRES_PASSWORD`, `ZERO_POSTGRESQL_TABLE` |
| `redis` | `ZERO_REDIS_URI` |

```powershell
mvn -B -ntp -q -f examples/repository-composition/pom.xml exec:java -Dexec.args=mongo
```

Use `postgresql` or `redis` for the other backends. The runtime starts the selected adapter and performs its health check. The example writes a unique record in `composition_example/balances` and deletes that record afterwards. PostgreSQL initializes its configured envelope table on first repository creation. Use a test database; no external run is part of default acceptance.

Repository creation and the existing envelope store operations may block. Call them from an appropriate managed execution domain. Closing the runtime closes repository access before releasing its clients; a retained repository rejects subsequent calls. Application roles never select the first source implicitly. Definitions are immutable and should be reused; registering a different definition under the same repository name or storage collection is rejected.

## Driver Contract Matrix

From the repository root, with Docker Desktop running and current artifacts installed:

```powershell
./scripts/VerifyRepositoryDrivers.ps1 -Plan
./scripts/VerifyRepositoryDrivers.ps1
```

The runner creates a unique Compose project using MongoDB 7.0, PostgreSQL 16 and Redis 7.2.
The runner selects free random ports on `127.0.0.1` and fixes them for the entire run, including restarts.
If another process claims a selected port before Docker binds it, setup fails explicitly.
It runs the same `BalanceService` contract for
all four backends: CRUD, stale-version rejection, the same entity ID in distinct namespaces,
and retained repository access after runtime close. External backends also reopen clients
and read the persisted values. Each test deletes its own records; the runner removes only
its Compose project and volumes in `finally`. Logs, image IDs and Failsafe summaries are
written under `target/repository-driver-verify/<run>/`. Service failures fail the run instead
of skipping it. Local memory does not persist across runtime recreation.

For an existing dedicated test service, set the environment variables above and run the
explicit profile, replacing `mongo` with `postgresql` or `redis`:

```powershell
mvn -B -ntp -q -f examples/repository-composition/pom.xml -Prepository-external '-Dzero.repository.backend=mongo' verify
```

Mongo collection names now use `z_<UTF-8 hex namespace>__<UTF-8 hex collection>`.
The database name, separator and encoded collection must fit 235 bytes; invalid Unicode or
long names are rejected before selecting a collection. Old sanitized collection names are
not read automatically. Existing development data must be explicitly migrated or recreated.

## Recovery And Sustained Workload

Use the same isolated runner to include recovery, concurrent CAS and sustained operation checks:

```powershell
./scripts/VerifyRepositoryDrivers.ps1 -Plan -Resilience
./scripts/VerifyRepositoryDrivers.ps1 -Resilience
./scripts/VerifyRepositoryDrivers.ps1 -Resilience -SoakSeconds 1800
```

After the base contracts, the runner checks these phases in order:

1. Gracefully stop and start each external database. The original runtime, client and repository
   must report `READ_FAILED` during the outage, read the saved balance after restart, and accept
   another credit. Only the runner's uniquely named and label-checked Compose project may be stopped.
2. Four workers race to create an absent record and update one shared version. Each race must have
   exactly one winner. Then each worker completes 100 business credits; all clients must read the
   exact final balance and version. External workers use independent runtimes and driver clients;
   local workers share the runtime-owned memory repository, since separate local runtimes do not share data.
3. All four backends run concurrently for 300 seconds each by default, with four workers per backend
   and a 10 ms pause after each credit. Every worker must make progress, and all final versions and
   balances must equal the successful credit count. Duration is configurable from 1 to 86400 seconds.

Only explicit `VERSION_CONFLICT` failures are retried, with a 1000-attempt bound and a 1 ms pause.
Transport errors fail the workload; an uncertain write outcome must not cause a blind repeated credit.
This policy belongs to the test harness. `BalanceService` does not add automatic production retries.
The fixture sets Mongo/Redis native timeouts to 2 seconds through the existing adapter timeout setting;
the runner supplies PostgreSQL `connectTimeout=2&socketTimeout=2` in its dedicated JDBC URL.
Worker waits and Docker commands are bounded, with an outer Failsafe process timeout of the requested
soak duration plus 600 seconds. These settings do not change application defaults.

The isolated Redis service explicitly uses AOF with `appendfsync always`. Recovery demonstrates a
graceful service stop/start with that persistence policy, not power-loss or network-partition safety.
`resilience.log` records progress every 30 seconds, successful credits, rejected CAS attempts and
mean/maximum credit latency including conflict retries. `resilience-summary.xml` records test results.
The four simultaneous workloads share the host; these numbers are observations of this consistency
workload, not comparable capacity measurements. PostgreSQL uses the runtime-owned HikariCP pool.
Five minutes does not establish long-term stability or production readiness. Network faults, ambiguous
write reconciliation, process crashes and resource growth over hours remain separate validation work.

The PostgreSQL runtime integration now reuses up to eight physical connections by default.
`PostgresqlRuntime.module(16)` sets a different maximum. Pool acquisition uses the adapter timeout;
JDBC `connectTimeout` and `socketTimeout` still govern physical connection and socket operations.
The pool is created only for a selected provider, opens connections lazily, and closes after repository
access is invalidated. HikariCP is a dependency of `zero-runtime-postgresql` only. Applications choosing
another pool can use `PostgresqlDataAdapter.repositoryFactory(dataSource, tableName)` in their own provider;
the caller owns that `DataSource` and must register its cleanup. Borrowed connections must use auto-commit;
manual transaction connections are rejected and returned without issuing statements or committing them.
The direct settings-based adapter factory
still creates unpooled connections and is intended for low-rate tools, not sustained request traffic.
