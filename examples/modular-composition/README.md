# Independent Modular Consumers

Five standalone Maven consumers verify that runtime selection also works with a reduced dependency graph:

| Consumer | Declared integration | Verified behavior |
| --- | --- | --- |
| `minimal` | Bootstrap | Start and close without component or middleware classes |
| `event-actor` | Bootstrap, event, actor | Dispatch an event into an Actor lane |
| `discovery` | Bootstrap, discovery | Register, query and unregister without Nacos |
| `center-logic` | Bootstrap, RPC | Invoke a shared center interface from logic without middleware |
| `redis` | Redis | Validate configuration, build and close without other middleware SDKs |

From the repository root, using Java 21 and Maven 3.9+:

```powershell
mvn -B -ntp -q -DskipTests install
mvn -B -ntp -q -f examples/modular-composition/pom.xml clean verify
```

The parent POM bans unrelated adapters, SDKs, Starters and runtime code generators using Maven Enforcer. Tests additionally assert absent classes on the actual consumer classpath. Redis uses an unreachable local address and deliberately does not start the runtime or perform network operations.

See the [composition guide](../../docs/guides/modular-composition-guide.zh-CN.md) for component selection, replacement and resource ownership.
