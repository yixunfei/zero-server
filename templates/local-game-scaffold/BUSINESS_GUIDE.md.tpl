# __PROJECT_NAME__ Business Guide

This guide is the first stop for business developers working inside this generated zeroServer local/prototype project.

## 1. Start Here

Use this project to understand the smallest business loop for:

- Template: `__TEMPLATE_NAME__`
- Use case: __TEMPLATE_USE_CASE__
- Protocol file: `__PROTOCOL_FILE__`
- Expected run summary: `__SUMMARY_PREFIX__`

Use this order for the first change:

1. `README.md` for run commands.
2. Edit the handwritten business files below.
3. Run the checks in `Local Verification` below.

Read `COMPONENTS.md` and `NEXT_STEPS.md` when you need framework boundaries or a promotion checklist.
`zero-scaffold.json` is machine-readable metadata for tools; it is not required for the first run.

## 2. Where To Write Business Logic

For the first demo change, start with `__APP_CLASS__.java` and `__TEST_CLASS__.java` (see `First Business Change`). The demo currently registers its own nested `LocalGameBO` fixture.

For reusable asynchronous business handlers, use the separate files:

~~~text
src/main/java/__PACKAGE_PATH__/LocalGameBO.java
src/main/java/__PACKAGE_PATH__/LocalGameFlow.java
src/main/java/__PACKAGE_PATH__/LocalGameFixture.java
src/main/java/__PACKAGE_PATH__/LocalGameObservation.java
~~~

The separate `LocalGameBO.java` adapts generated protocol events to injected asynchronous business ports. Services return `CompletionStage`; handlers must compose stages and must not call `join()` or `get()`. Changes to this separate adapter do not automatically change the demo's nested fixture.

The local fixture explicitly selects `MissingPlayerPolicy.CREATE_DEFAULT` to create prototype players. The framework default is `REJECT`. When connecting an account system, register player profiles through your repository before login, use the default rejection policy, and implement authentication and account-to-UID authorization in the business boundary.

## 3. Where To Change Protocol

Protocol input lives in:

~~~text
__PROTOCOL_FILE__
src/main/protocol/protoId.txt
~~~

After changing protocol files, run from the zeroServer repository root (replace the project path):

~~~powershell
./mvnw.cmd -q -f "path/to/__PROJECT_NAME__/pom.xml" generate-sources
~~~

On Linux/macOS use `./mvnw`. Generated sources are written to:

~~~text
target/generated-sources/zero-codegen
~~~

Treat generated files as output. Put business behavior in handwritten code, not inside generated sources.

## 4. Local Verification

From the zeroServer repository root, test and run with the bundled Wrapper:

~~~powershell
java scripts/RunLocalScaffold.java --projectDir "path/to/__PROJECT_NAME__"
~~~

For a test-only feedback loop from the repository root:

~~~powershell
./mvnw.cmd -q -f "path/to/__PROJECT_NAME__/pom.xml" test
~~~

On Linux/macOS use `./mvnw`. With Maven 3.9+ on PATH, you may also run `mvn -q test` and `mvn -q exec:java` inside the generated project.

For a fast structure-only check, use:

~~~powershell
java scripts/RunLocalScaffold.java --projectDir path/to/__PROJECT_NAME__ --skipTests --skipRun
~~~

## 5. Safe Local Changes

Good first changes:

- Add a new request to `__PROTOCOL_FILE__`.
- Extend a demo handler in `__APP_CLASS__`, or a reusable asynchronous handler in the separate `LocalGameBO.java`.
- Add assertions to `src/test/java/__PACKAGE_PATH__/__TEST_CLASS__.java`.
- Add local log or metric samples through existing framework components.

Avoid in this local scaffold:

- Directly connecting Kafka, MongoDB, Redis, PostgreSQL or Nacos.
- Opening production network ports.
- Creating unmanaged thread pools.
- Treating generated code as handwritten source.
- Freezing public API, protocol compatibility, storage format, cache strategy or GM/security behavior.

## 6. First Business Change

A first change can use the existing move request without adding a protocol:

Suggested path:

1. In `src/main/java/__PACKAGE_PATH__/__APP_CLASS__.java`, find `moveRequest()` and change `request.x = 3` / `request.y = 5` to `4` / `6`.
2. Run the test-only command above and observe the position assertions fail.
3. Update the expected position in `src/test/java/__PACKAGE_PATH__/__TEST_CLASS__.java` to `(4, 6)`, then test and run again. The summary should contain `position=4,6`.

To add a new player action later, add a request to `__PROTOCOL_FILE__`, reserve its protocol id in `src/main/protocol/protoId.txt`, and regenerate sources. Implement its generated event interface in a handwritten handler and register that handler with the generated dispatcher. The demo uses `registerHandlers()` in `__APP_CLASS__.java`; reusable flows use `LocalGameFlow.java`.

Keep the first change local and deterministic. Do not add account authentication, durable online state or external persistence from inside this scaffold.

## 7. Production Boundary

Production gap:

~~~text
__PRODUCTION_GAP__
~~~

Before promoting this scaffold into a formal framework module, create a task with REQUIREMENTS / PLAN / RISK / VERIFY, update `docs/module-map.md` if module structure changes, and pause for user confirmation if the work touches public API, protocol, threading, RPC, storage, cache, GM/security, logs or ErrorCode.
