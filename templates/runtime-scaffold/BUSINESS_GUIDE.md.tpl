# Business Guide

## Start Here

`RuntimeAssembly.java` is the composition root. `__APP_CLASS__.java` owns its lifetime.

## Where To Write Business Logic

Inject the selected interface into a business service. For `data` or `redis`, the generated
assembly binds repository role `main` to `local` or `redis`; retrieve `DataRuntime.REPOSITORIES`
and create a typed repository using a `RepositoryRequest` and `RepositoryDefinition`.

## Where To Change Protocol

This template has no generated protocol. Add protocol generation only when the application needs it.

## Local Verification

Run `mvn -q clean test exec:java`. Redis is not started by the default smoke test.

## First Business Change

Register a provider in `RuntimeAssembly`, then select it with `override(key, providerId)`.
The `custom-actor` selection includes a lazy provider example using `LocalActorScheduler`.

## Production Boundary

Configure external service credentials outside source control. Confirm real service connectivity,
business read/write behavior, capacity and failure handling before deployment.
