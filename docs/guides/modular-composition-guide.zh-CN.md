# 按需装配与实现替换

业务工程使用 `zero-bom` 对齐版本，只声明需要的集成模块。`zero-server-starter` 和 `zero-server-starter-production` 是全量便利组合包；需要精简依赖时，从下面的独立入口开始。

## 1. 最小运行时

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>group.zn.zero</groupId>
      <artifactId>zero-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency>
    <groupId>group.zn.zero</groupId>
    <artifactId>zero-runtime-bootstrap</artifactId>
  </dependency>
</dependencies>
```

```java
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;

try (GameRuntime runtime = RuntimeBasics.builder().build()) {
    runtime.start();
}
```

这一路径只引入配置、执行器和运行时内核，不包含 Actor、网络或数据库。默认直接执行器不创建线程池。

## 2. 选择组件

| Maven 依赖 | Java 装配入口 | 主要能力键 |
| --- | --- | --- |
| `zero-runtime-bootstrap` | `RuntimeBasics.builder()` / `module(config, executors)` | `CONFIG`、`EXECUTORS` |
| `zero-runtime-event` | `EventRuntime.module()` | `EVENT_BUS`、`DEAD_LETTER_SINK` |
| `zero-runtime-actor` | `ActorRuntime.module()` | `ACTOR_SCHEDULER` |
| `zero-runtime-protocol` | `ProtocolRuntime.module()` | `PROTOCOL_REGISTRY` |
| `zero-runtime-rpc` | `RpcRuntime.module()` | `RPC_TRANSPORT`、`RPC_HANDLER_REGISTRY`、`RPC_SERVICE_RESOLVER` |
| `zero-runtime-data` | `DataRuntime.module()` | `PERSISTENCE_MANAGER`、`DATA_SERVICES` |
| `zero-runtime-cache` | `CacheRuntime.module()` | `CACHE_SERVICE` |
| `zero-runtime-log` | `LogRuntime.module()` | `LOG_APPENDER`、`TERMINAL_LOG_SINK` |
| `zero-runtime-monitor` | `MonitorRuntimeComponent.module()` | `MONITOR_RUNTIME` |
| `zero-runtime-discovery` | `DiscoveryRuntime.module()` | `SERVICE_DISCOVERY` |

每个入口位于 `group.zn.zero.runtime.<组件>` 包。Maven 依赖声明可用的代码，`install(...)` 声明参与当前运行时的组件。组件需要的运行时依赖仍须显式安装；缺失时 `diagnose()` 在创建资源前失败。

例如，声明 `zero-runtime-bootstrap`、`zero-runtime-event`、`zero-runtime-actor` 后：

```java
try (GameRuntime runtime = RuntimeBasics.builder()
        .install(EventRuntime.module())
        .install(ActorRuntime.module())
        .build()) {
    runtime.start();
    ActorScheduler actors = runtime.require(ActorRuntime.ACTOR_SCHEDULER);
    EventBus events = runtime.require(EventRuntime.EVENT_BUS);
}
```

完整业务调用见 [event-actor 独立消费者](../../examples/modular-composition/event-actor/src/test/java/group/zn/zero/examples/composition/EventActorConsumerTest.java)。

## 3. 替换实现

已有实例可以通过 `.replace(CacheRuntime.CACHE_SERVICE, applicationCache)` 替换。需要读取其他能力或管理资源时，注册 provider 并显式选择：

```java
ComponentId id = ComponentId.of("application.cache");
RuntimeComponentProvider provider = RuntimeProviders.create(
        ComponentDescriptor.builder(id)
                .provide(CacheRuntime.CACHE_SERVICE)
                .require(RuntimeBasics.CONFIG)
                .kind(ComponentKind.BUSINESS)
                .build(),
        context -> ComponentContribution.builder()
                .bind(CacheRuntime.CACHE_SERVICE,
                        createApplicationCache(context.require(RuntimeBasics.CONFIG)))
                .build());

RuntimeComposition composition = RuntimeBasics.builder(config)
        .install(CacheRuntime.module())
        .register(provider)
        .override(CacheRuntime.CACHE_SERVICE, id);
composition.diagnose();
```

`createApplicationCache` 由应用实现。依赖该能力的 provider 会取得所选实例。默认实现未被选中时不执行工厂。`RuntimeModule` 可封装一组 provider、默认选择和配置源；重复 provider ID、冲突和循环由现有规划器拒绝。

## 4. 单个真实 Adapter

| 依赖 | 显式接入入口 |
| --- | --- |
| `zero-runtime-kafka` | `KafkaRuntime.module()` |
| `zero-runtime-mongo` | `MongoRuntime.module()` |
| `zero-runtime-redis` | `RedisRuntime.module()` / `module(cacheCodec)` |
| `zero-runtime-postgresql` | `PostgresqlRuntime.module()` |
| `zero-runtime-nacos` | `NacosRuntime.module()` |
| `zero-runtime-net` | `NetworkRuntime.module(policy, rateLimiter)` |

这些模块单向依赖共享的 `zero-runtime-production` 与各自 Adapter，不依赖全量 Starter 或其他驱动。保留严格的 enabled/mode 配置规则：安装集成模块后还需显式启用所需能力。

只依赖 `zero-runtime-redis` 的工程可以：

```java
ZeroConfig config = new MapZeroConfig(Map.of(
        "zero.adapter.data.redis.enabled", "true",
        "zero.redis.uri", "redis://127.0.0.1:6379"));
ProductionAssembly assembly = ProductionAssembly.builder(config)
        .install(RedisRuntime.module());
ZeroProductionAssemblyReport report = assembly.diagnose();
try (ZeroProductionRuntime runtime = assembly.build()) {
    runtime.start();
}
```

具体配置键以 `ZeroProductionRuntimeConfigKeys` 和 [Redis 消费者](../../examples/modular-composition/redis/src/test/java/group/zn/zero/examples/composition/RedisConsumerTest.java) 为准。`diagnose()` 不创建 client；`build()` 创建受管资源，`start()` 执行真实健康检查。Kafka 还需要日志能力；network 需要显式策略、日志、监控和不会内联远程 IO 的执行器，传入 `null` rateLimiter 使用默认限流器。

应用扩展通过 `ProductionModuleFactory` 或 `.configure(composition -> ...)` 注册。Adapter 默认选择先应用，应用的显式 override 后应用。自定义模块不需要修改全量 Starter 的固定组件列表。

network 的执行器实例约束在选中的 provider 创建后校验；`replace(RuntimeBasics.EXECUTORS, executors)` 和延迟 `base(RuntimeBasics.module(config, supplier))` 都可以使用。`diagnose()` 不调用 supplier，因此不能验证尚未创建的执行器是否内联；`build()` 仍拒绝内联 remote IO，并执行资源回滚。

## 5. 生命周期和配置

`ProductionAssembly.builder("standalone", config)` 可用于单进程按需装配；同一 overload 支持 `external-test` 和 `production`。档位必须与配置中 `zero.mode` 一致，不会隐式启用 Adapter。`diagnose()` 汇总缺失配置；`plan()` 要求配置完整，返回不可变 provider 图，二者均不创建客户端或执行器。生成的外部 runtime 装配从配置读取档位。

typed Provider 的业务配置使用 `context.config()`。`configSource(...)` 覆盖的是 schema 声明的键；`RuntimeBasics.CONFIG` 除 `zero.mode`/`zero.name` 外仍保留原 ZeroConfig 字符串，不自动合并所有 ConfigSource。

- 组合器与 runtime 都是单次使用。`diagnose()` 可重复调用，`build()` 消耗组合器，即使构建失败也不能重试该实例。
- 模块默认配置源排在显式 `configSource(...)` 之后。typed schema 在 provider 创建前完成校验。
- 框架创建的资源立即登记 `context.resources()`，由账本负责失败回滚和逆序关闭。
- `RuntimeBasics.module(config, executors)` 的执行器只在其 provider 被创建后转交 runtime 管理。规划失败或该 provider 未被选中时，调用方仍负责关闭。
- `RuntimeBasics.module(config, () -> executors)` 延迟到 provider 被选择且规划成功后才调用工厂；需要创建线程池时优先使用此形式。
- `.replace(...)` 接收的实例由调用方拥有。实现 `Lifecycle` 的实例参与 start/stop；只有显式登记的 `AutoCloseable` 才进入资源账本。
- `LocalRuntimeBuilder.logAppender()` 是可供启动观察器保留的转发入口，写入必须发生在成功 build 后；它转发到所选 `LogRuntime.LOG_APPENDER`，不保证对象身份相同。

## 6. 0.x 迁移

旧 `LocalRuntimeCapabilities`、`ProductionRuntimeCapabilities` 已删除。按上表从对应组件集成入口取得能力键；生命周期集合迁至 `RuntimeLifecycleCapabilities`。

| 原位置 | 当前归属 |
| --- | --- |
| `group.zn.zero.starter.ZeroRuntimeExecutors`、`ZeroRuntimeConfigKeys` | `group.zn.zero.runtime.bootstrap` |
| Starter 内的生产 Runtime、报告、配置与错误类型 | `group.zn.zero.runtime.production` |
| Nacos 包中的 `ServiceDiscovery`、实例、查询、订阅和本地实现 | `group.zn.zero.discovery`，依赖 `zero-discovery` |
| Nacos 包中的 RPC resolver 与 metadata mapper | `group.zn.zero.rpc.discovery`，依赖 `zero-rpc-discovery` |
| Starter 内的真实 Adapter provider | 对应 `zero-runtime-*` 集成模块，通过公开模块工厂接入 |

## 7. 可重复验收

```powershell
mvn -B -ntp -q -DskipTests install
mvn -B -ntp -q -f examples/modular-composition/pom.xml clean verify
java scripts/ZeroStage0Acceptance.java --level full
```

独立消费者使用 Maven Enforcer 检查传递依赖，再通过实际 classpath 启动和类缺席断言验证隔离。单 Redis 消费者只创建与关闭 client，不连接外部 Redis；真实健康、读写与故障恢复需要外部服务验证。

## 8. Repository 与按需脚手架

`DataRuntime.REPOSITORY_SOURCES` 提供命名源，`DataRuntime.repositories(Map.of("game", "local"))` 生成显式角色目录，业务从 `DataRuntime.REPOSITORIES` 创建中立 Repository。支持本地、MongoDB、PostgreSQL、Redis 数据实现，详见 [Repository 接入指南](repository-composition-guide.zh-CN.md)。

脚手架支持最小 `runtime` 模板及 `--components` 选择：

```powershell
java scripts/NewLocalGame.java --template runtime --components event,actor --projectName my-runtime --outputDir target/my-runtime
mvn -q -f target/my-runtime/pom.xml clean test exec:java
java scripts/VerifyGeneratedCompositions.java
```

生成器同步输出 POM、`RuntimeAssembly.java`、配置样例和所选 provider 清单。七类业务模板的 codegen 只作为构建插件依赖；纯 runtime 模板不引入 codegen。生成验收覆盖 25 个消费者，包括每个公开组件、混合组合、中心 RPC 和分布式基础设施组合。参数与消费路径见[脚手架目录](../scaffold-templates.zh-CN.md)。

生成的 `RuntimeAssembly.diagnose(...)`、`plan(...)` 与 `create(...)` 共享组装定义。runtime 模板可执行 `mvn -q exec:java '-Dexec.args=--diagnose'`，只规划与诊断配置，不创建执行器或客户端；外部组件需要检查报告中的 `missingConfigKeys`，再通过 `--start` 显式启动。默认外部 smoke 也只诊断，缺配置输出 incomplete，齐全输出 ok。迁移方式见[0.x 说明](../migrations/20260914-scenario-composition.md)。
