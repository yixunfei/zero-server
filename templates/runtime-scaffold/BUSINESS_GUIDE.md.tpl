# Business Guide

## Start Here

`RuntimeAssembly.java` is the composition root. `__APP_CLASS__.java` owns its lifetime.

## Where To Write Business Logic

Start with one service and inject only the capabilities it needs. If you selected a data component, the generated
assembly binds repository role `main` to that source. Multiple sources use their own names
(`local`, `redis`, `mongo`, `postgresql`); edit the role map for your business. Retrieve `DataRuntime.REPOSITORIES`
and create a typed repository using a `RepositoryRequest` and `RepositoryDefinition`.

## Where To Change Protocol

This template has no generated protocol. Add protocol generation only when the application needs it.

## Local Verification

Run `./mvnw -q clean test exec:java`. External adapters are only diagnosed by the default smoke test.

## First Business Change

Inside `RuntimeAssembly.composition`, install a service with its dependencies:

```java
var greeting = group.zn.zero.runtime.api.ComponentKey.single("app.greeting", String.class);
assembly.install(group.zn.zero.runtime.assembly.RuntimeModule.component(
        group.zn.zero.runtime.api.ComponentId.of("app.greeting-provider"), greeting)
        .require(RuntimeBasics.CONFIG)
        .create(context -> "hello " + context.require(RuntimeBasics.CONFIG).getOrDefault("zero.name", "app")));
```

Read it with `runtime.require(greeting)`. Planning checks dependencies without running the factory.
Factory-created `AutoCloseable` values belong to the runtime and are closed on normal shutdown or rollback;
`Lifecycle` values participate in start/stop. Use `RuntimeModule.value(id, key, value)` for borrowed,
already-created values. Multiple bindings, optional dependencies and custom health use the explicit Provider API.
PowerShell commands use `./mvnw.cmd` instead of `./mvnw`.

## Production Boundary

Configure external service credentials outside source control. Confirm real service connectivity,
business read/write behavior, capacity and failure handling before deployment.
