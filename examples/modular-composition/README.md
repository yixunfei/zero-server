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

### Redis 执行域

Redis 同步驱动示例显式使用 `ProductionAssembly.builder("production", config, ZeroRuntimeExecutors.remoteIo("redis-consumer"))`，默认 direct IO 会被 provider 拒绝。外围用 try-with-resources 管理 executors，确保 build 失败也关闭；成功后 runtime 接管关闭。仅 build/close 不连接 Redis，实际读写与外部集成另行验证。
