# 配置、装配诊断与显式组件目录

本指南适用于当前 checkout 的易用性基础改造。框架仍处于开发预览阶段，`productionReady=false`。

## 先跑最小项目

使用 JDK 21，在仓库根目录用 Runner 准备所选开发坐标（`0.1.2-SNAPSHOT`，仅安装到本地 Maven 仓库）：

```shell
java scripts/RunLocalPrototype.java --template runtime --projectName minimal-demo --packageName group.zn.demo --outputDir target/minimal-demo
cd target/minimal-demo
./mvnw -q clean test exec:java
./mvnw '-Dexec.mainClass=group.zn.demo.RuntimeAssembly' exec:java
```

Windows 使用 `./mvnw.cmd`，`-D...` 参数整体加引号。生成项目自带 Wrapper；框架源码更新后重新运行 Runner（不加 `--skipInstall`）刷新所需构件。纯 runtime 默认仅装配配置和执行器；业务模板按需要安装 Actor、日志和监控。诊断入口 `RuntimeAssembly.main` 不启动业务、线程池或外部客户端。

## 一个值、一份来源

标准消费顺序为：

| 优先级 | 来源 | 使用方法 |
| --- | --- | --- |
| 1 | 程序化输入 / 显式文件 | `MapZeroConfig` / `ZeroConfigLoader.fromPropertiesFile` |
| 2 | JVM 属性 | 逻辑键，如 `-Dzero.name=demo` |
| 3 | 环境变量 | 标准键将点、连字符转为下划线并大写；Adapter 可声明专用 alias |
| 4 | 应用默认值 | `SourcedZeroConfig.withDefaults` / `ZeroConfigLoader.loadStandard(defaults)` |
| 5 | typed schema 默认值 | `ConfigKey.defaultValue` |

`ConfigLookup` 负责候选值及来源，decoder 负责类型和业务校验。空白或非法的首选值不会自动退回更低优先级；Adapter 明确声明的 `readDefaultable` 空白语义仍适用。开关、档位选择器和预算也使用同一读取规则。

`ZeroConfigLoader.loadStandard(defaults)` 只查询文件或 defaults 已声明的键，避免扫描整个环境；文件路径仍由 `zero.config.file` 或 `ZERO_CONFIG_FILE` 指定。外部 Adapter 的其他键由其明确的 alias 列表查询。每个 `ConfigLookup` 会话冻结已读取属性和环境值，包括缺失值；不会跨会话缓存进程配置。

`SourcedZeroConfig` 与 `ZeroConfig.origin(key)` 保留 FILE、DEFAULT 等归因。不要为了合并默认值转成 `new MapZeroConfig(config.asMap())`，这样会把来源改成程序化输入。程序化 `MapZeroConfig` 的值始终表示显式覆盖。

低层 `RuntimeComposition.configSource(...)` 是应用显式装配接口，仍按注册顺序优先于模块来源；它不会偷偷读取进程环境。`ConfigSource.kind()` 表示输入通道，`origin(alias)` 保留该通道内单个值的真实来源，typed allowlist 仍校验输入通道。诊断中的来源类别取自 `origin`。

## 读取装配结果

生成的本地和外部项目统一提供：

```java
RuntimeAssemblyDiagnosis diagnosis = RuntimeAssembly.diagnose(config);
diagnosis.missingConfigKeys();       // 排序且不可变；外部配置不完整时可用于提示
diagnosis.plan();                    // 完整时包含实际组件图
RuntimeAssemblyPlan plan = RuntimeAssembly.plan(config); // 缺配置或非法配置直接失败
plan.configuration().entries();     // 来源、alias、类型、状态、reloadability、脱敏值
```

业务模板的上述方法额外接收 `LogSink`。底层组合器对应 `RuntimeComposition.diagnose()` 与 `ProductionAssembly.diagnosis()`；后者一次解析得到统一诊断，原有 Adapter 运行状态报告仍由 `ProductionAssembly.diagnose()` 提供。

计划的配置诊断来自同一个 `ResolvedRuntimeConfig`，运行后的 `runtime.plan().configuration()` 反映该次构建的 typed 输入。不同次 `diagnose()` / `build()` 会重新解析可变来源；需要精确对应运行实例时读取实例的计划。只有声明了 schema 的键才会出现在 typed 诊断中，不能把空条目理解为组件无需配置。

敏感值固定显示 `***`，复杂类型只显示类型占位；不输出 decoder 异常原文。诊断不证明外部连接、owner 恢复、容量或生产安全链已经可用。

## 显式扩展目录

```shell
java scripts/NewLocalGame.java --catalog examples/scaffold-catalog/greeting.catalog.json --listComponents
java scripts/RunLocalPrototype.java --catalog examples/scaffold-catalog/greeting.catalog.json --template runtime --components greeting --projectName greeting-demo --packageName group.zn.greeting --outputDir target/greeting-demo
./mvnw -f target/greeting-demo/pom.xml test exec:java
./mvnw -f target/greeting-demo/pom.xml -Dexec.mainClass=group.zn.greeting.RuntimeAssembly exec:java
```

示例会注册 `application.greeting` 并提供 `sample.greeting` 字符串能力，生成项目的测试将实际装配图与 manifest 对照。它没有第三方依赖，方便在最小环境中验证扩展路径。

JSON 的 `schemaVersion` 为 `1`，`id` 是应用自己的目录身份。普通单值组件使用 `bindings`，只声明一次组件、能力、类型、依赖和工厂；生成器从同一声明派生能力图、Provider 元数据和 Java 安装代码。见 [greeting 示例](../../examples/scaffold-catalog/greeting.catalog.json)。复杂多值组件仍可使用 `capabilities` / `providers` / `components`。未知字段、未知 ID、重复组件/Provider、循环依赖和不支持的 profile 会在生成前失败。

JSON 扩展是对标准目录的追加；不会自动替换内置项。第三方安装表达式必须来自可信应用代码：生成器只写入表达式，不加载类，但生成项目运行时会执行它。所有 Maven 坐标当前使用生成项目的 `${zero.version}`；第三方版本不同需在应用 POM 明确调整。需要整体替换目录时，可使用 `ScaffoldComponents(id, model, definitions)` Java API。

生成的 `zero-scaffold.json` 记录 `catalogId` 和 `catalogSchemaVersion`。升级时传入相同 `--catalog` 及组件参数，例如将上述生成命令追加 `--plan`，审阅后使用 `--apply`。Java API 对应 `new ScaffoldUpgradeService(components)`。使用不同目录升级会明确报 `SCAFFOLD-CATALOG-MISMATCH`；有用户修改冲突时仍不覆盖。

## 单值组件的最短接入

```java
var greeting = ComponentKey.single("sample.greeting", String.class);
assembly.install(RuntimeModule.component(ComponentId.of("application.greeting"), greeting)
        .require(RuntimeBasics.CONFIG)
        .create(context -> "hello " + context.require(RuntimeBasics.CONFIG).getOrDefault("zero.name", "app")));
```

工厂在 build 时执行，规划先校验依赖。返回的 `AutoCloseable` 由 runtime 拥有并登记回滚；`Lifecycle` 自动参与 start/stop。工厂内部提前创建的中间资源仍需立即调用 `context.resources().register(...)`。已有共享对象使用 `RuntimeModule.value(id, key, value)`，该入口不接管 AutoCloseable 所有权。

catalog 的等价工厂如下。`factory` 是可信 Java 表达式，参数固定为 `context`；类型采用完整 Java 类名。省略 `artifacts` 时依赖 `zero-runtime`；外部类型须声明其坐标。

```json
{
  "schemaVersion": 1,
  "id": "example.config-greeting.v1",
  "bindings": [{
    "id": "greeting",
    "provider": "application.greeting",
    "key": "sample.greeting",
    "type": "java.lang.String",
    "requires": [{"key": "zero.config", "type": "group.zn.zero.core.config.ZeroConfig"}],
    "factory": "context.require(group.zn.zero.runtime.bootstrap.RuntimeBasics.CONFIG).getOrDefault(\"zero.name\", \"app\")"
  }]
}
```

## 外部能力、运行期健康与预算

`ProductionAssembly.builder(config)` 默认在 build 阶段创建并托管 remote IO；diagnose/plan 不创建执行器或客户端。显式 direct IO 返回 `REMOTE_IO_EXECUTOR_REQUIRED`；自定义 base 的执行器工厂在 build 时校验，不会为了提前校验而执行工厂。

`GameRuntime.healthSnapshot()` 保留 STARTUP 历史并提供 READINESS 当前结果。只有 HEALTHY 表示允许新请求；远程 Adapter 降级/恢复中拒绝请求，对应 UNHEALTHY。非 RUNNING 的 runtime 不报告就绪。读取只取内存快照，不发起探测。自定义组件可用 `ComponentContribution.healthSnapshot(phase, supplier)` 接入；supplier 必须线程安全、非阻塞且无 IO。

`ZeroProductionRuntime.productionReport().adapterStatus(name)` 中，`runtime()` 含运行状态、稳定原因码、在途数和恢复决策；`settings()` 含有效运行预算，在 `ProductionAssembly.diagnose()` 中也可查看。启动失败字段与运行期恢复快照分别表达不同阶段。

| 配置键 | 默认值 | 作用 |
| --- | --- | --- |
| `zero.adapter.<adapter>.operation-timeout-millis` | Redis/MongoDB/PostgreSQL：10000；Kafka：3000；Nacos：SDK 配置默认值 | 原生 IO 与探测超时上限，独立于启动预算 |
| `zero.adapter.<adapter>.max-in-flight` | 128 | 最大在途操作；超限直接拒绝 |
| `zero.adapter.<adapter>.probe-interval-millis` | 2000 | 固定探测间隔，不重发业务操作 |

`<adapter>` 为 `redis`、`mongo`、`postgresql`、`kafka`、`nacos`；配置支持文件、JVM 属性及标准环境变量别名。Redis 数据与缓存共享预算。PostgreSQL 原生超时最低 1000 ms；Nacos 显式原生 request timeout 不得超过运行预算，启动窗口仍须容纳 SDK 启动时间。设置启动预算不再隐式修改运行期超时。原生驱动可能有多个阶段或忽略中断，因此这不是任意 SDK 的硬截止保证。

健康恢复只表示允许后续请求，不代表之前的未知写入已确认。`WRITE_OUTCOME_UNKNOWN` 仍需应用对账，框架不自动重发。
