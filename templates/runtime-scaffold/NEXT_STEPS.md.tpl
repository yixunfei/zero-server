# Next Steps

## Production Gap

__PRODUCTION_GAP__

## Promotion Checklist

Verify selected dependency graphs, real service read/write behavior, lifecycle rollback and application budgets.
Use `mvn clean test exec:java` for this runtime template; `RunLocalScaffold` covers the seven business templates.

## High-Risk Stop Points

Review schema changes and external writes against application requirements.

## Design Proposal

Keep implementation choices in `RuntimeAssembly` and depend on narrow interfaces in business services.
