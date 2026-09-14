# __PROJECT_NAME__

Selected components: __SELECTED_COMPONENTS__.

```sh
mvn -q clean test
mvn -q exec:java -Dexec.args=--diagnose
mvn -q exec:java
```

Local components start and close without external services. External components default to
diagnosis only without creating clients; the summary reports `started=false`. To start adapters and run the
adapter health check, set `ZERO_CONFIG_FILE` to a properties file based on
`config/application.properties.example`, then run `mvn -q exec:java -Dexec.args=--start`.
The smoke run does not prove external connectivity or database reads/writes.

`--diagnose` resolves configuration and selection without creating executors or driver clients.
Missing configuration produces `runtime-diagnosis=incomplete`; otherwise the status is `ok`.
Check `missingConfigKeys` in an external report before starting. A completed diagnosis is not
a connectivity check. `--diagnose` and `--start` are separate commands.

External configuration may set `zero.mode` to `standalone`, `external-test` (default), or
`production`. `RuntimeAssembly.plan(config)` validates complete configuration and returns the
actual provider graph without creating resources. Kafka processes need distinct client IDs,
consumer groups, and reply topics. Database credentials must be supplied outside the source.

## Next Business Step

Read [BUSINESS_GUIDE.md](BUSINESS_GUIDE.md) and the generated `RuntimeAssembly.java`.

## First Business Change

Add a service that receives typed capabilities in its constructor. Select or replace providers
in `RuntimeAssembly`. [COMPONENTS.md](COMPONENTS.md), [NEXT_STEPS.md](NEXT_STEPS.md) and
`zero-scaffold.json` describe this project's selected dependencies.
