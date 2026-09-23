# Business Guide

## Start Here

`RuntimeAssembly.java` is the composition root. `__APP_CLASS__.java` owns its lifetime.

## Where To Write Business Logic

Inject the selected interface into a business service. For a single data source, the generated
assembly binds repository role `main` to that source. Multiple sources use their own names
(`local`, `redis`, `mongo`, `postgresql`); edit the role map for your business. Retrieve `DataRuntime.REPOSITORIES`
and create a typed repository using a `RepositoryRequest` and `RepositoryDefinition`.

## Where To Change Protocol

This template has no generated protocol. Add protocol generation only when the application needs it.

## Local Verification

Run `mvn -q clean test exec:java`. External adapters are only diagnosed by the default smoke test.

## First Business Change

Register a provider in `RuntimeAssembly`, then select it with `override(key, providerId)`.
The `custom-actor` selection includes a lazy provider example using `LocalActorScheduler`.

## Production Boundary

Configure external service credentials outside source control. Confirm real service connectivity,
business read/write behavior, capacity and failure handling before deployment.
