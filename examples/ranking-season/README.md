# Ranking season standalone example

This standalone Maven example demonstrates the forthcoming `zero-ranking` API as a deterministic local, in-memory flow. It does not change framework modules or provide a production backend.

## Run

Requires JDK 21 (the repository currently targets release 21):

```powershell
mvn -B -ntp -f examples/ranking-season/pom.xml clean test
mvn -B -ntp -f examples/ranking-season/pom.xml exec:java
```

The application demonstrates season create/open, explicit `SET`, `MAX`, and `ADD` score merges, stable bounded Top, freeze, immutable snapshot, dry-run and execute settlement, idempotent settlement replay, and archive.

The output starts with this stable marker shape:

```text
ranking-season=ok|mode=local|...|productionReady=false
```

The concrete marker uses `top=1002:240,1003:180`; volatile timestamps and implementation details are intentionally omitted.

## Boundaries

- One synchronous local owner; no Redis, database, network, rewards, or external threads.
- Only `OPEN` accepts score writes; Top is bounded to 100 entries.
- Ordering is score descending, then uid ascending.
- Settlement execute is a local state transition/notification record, not reward delivery.
- `productionReady=false` is intentional. Production capacity, persistence, cross-server ranking, cache policy, and reward delivery remain unspecified.
