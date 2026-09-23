# __PROJECT_NAME__ Business Guide

This guide is the first stop for business developers working inside this generated zeroServer local/prototype project.

## 1. Start Here

Use this project to understand the smallest business loop for:

- Template: `__TEMPLATE_NAME__`
- Use case: __TEMPLATE_USE_CASE__
- Protocol file: `__PROTOCOL_FILE__`
- Expected run summary: `__SUMMARY_PREFIX__`

Read these files in order:

1. `README.md` for run commands.
2. `BUSINESS_GUIDE.md` for business editing points.
3. `COMPONENTS.md` for framework component boundaries.
4. `NEXT_STEPS.md` for production promotion gaps and high-risk stop points.
5. `zero-scaffold.json` for machine-readable scaffold metadata.

## 2. Where To Write Business Logic

Start with:

~~~text
src/main/java/__PACKAGE_PATH__/LocalGameBO.java
src/main/java/__PACKAGE_PATH__/LocalGameFlow.java
src/main/java/__PACKAGE_PATH__/LocalGameFixture.java
src/main/java/__PACKAGE_PATH__/LocalGameObservation.java
~~~

`__APP_CLASS__.java` is the composition root only. `LocalGameBO` adapts generated protocol events to injected asynchronous business ports. Services return `CompletionStage`; handlers must compose stages and must not call `join()` or `get()`.

## 3. Where To Change Protocol

Protocol input lives in:

~~~text
__PROTOCOL_FILE__
src/main/protocol/protoId.txt
~~~

After changing protocol files, run:

~~~powershell
mvn -q generate-sources
~~~

Generated sources are written to:

~~~text
target/generated-sources/zero-codegen
~~~

Treat generated files as output. Put business behavior in handwritten code, not inside generated sources.

## 4. Local Verification

From the generated project directory:

~~~powershell
mvn -q clean test
mvn -q exec:java
~~~

From the zeroServer repository root:

~~~powershell
java scripts/InspectLocalScaffold.java --projectDir path/to/__PROJECT_NAME__
java scripts/RunLocalScaffold.java --projectDir path/to/__PROJECT_NAME__
~~~

Open a GitHub Design Proposal before extracting a formal module or changing a public contract.

For a fast structure-only check, use:

~~~powershell
java scripts/RunLocalScaffold.java --projectDir path/to/__PROJECT_NAME__ --skipTests --skipRun
~~~

## 5. Safe Local Changes

Good first changes:

- Add a new request to `__PROTOCOL_FILE__`.
- Add or extend a handwritten BO handler in `__APP_CLASS__`.
- Add assertions to `src/test/java/__PACKAGE_PATH__/__TEST_CLASS__.java`.
- Add local log or metric samples through existing framework components.

Avoid in this local scaffold:

- Directly connecting Kafka, MongoDB, Redis, PostgreSQL or Nacos.
- Opening production network ports.
- Creating unmanaged thread pools.
- Treating generated code as handwritten source.
- Freezing public API, protocol compatibility, storage format, cache strategy or GM/security behavior.

## 6. First Business Change

A safe first change for the local RPG-style scaffold is to add one small player action.

Suggested path:

1. Add a new request to `__PROTOCOL_FILE__`, for example a `sayHello(uid, message, traceId)` or `collectItem(uid, itemId, traceId)` command.
2. Reserve a protocol id in `src/main/protocol/protoId.txt`.
3. Run `mvn -q generate-sources`.
4. Implement the generated BO method in `__APP_CLASS__`.
5. Add one assertion to `__TEST_CLASS__` that checks the returned summary, log count or metric count.

Keep the first change local and deterministic. Do not add account authentication, durable online state or external persistence from inside this scaffold.

## 7. Production Boundary

Production gap:

~~~text
__PRODUCTION_GAP__
~~~

Before promoting this scaffold into a formal framework module, create a task with REQUIREMENTS / PLAN / RISK / VERIFY, update `docs/module-map.md` if module structure changes, and pause for user confirmation if the work touches public API, protocol, threading, RPC, storage, cache, GM/security, logs or ErrorCode.
