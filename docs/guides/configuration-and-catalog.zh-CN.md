# 配置、装配诊断与显式组件目录

本指南适用于当前 checkout 的易用性基础改造。框架仍处于开发预览阶段，`productionReady=false`。

## 先跑最小项目

使用 JDK 21，在仓库根目录构建并安装本地开发坐标（当前子模块为 `0.1.1`，根聚合 POM 为 `0.1.2-SNAPSHOT`；以下不是发布操作）：

```shell
./mvnw -pl zero-codegen,zero-runtime-bootstrap -am -DskipTests install
java scripts/NewLocalGame.java --template runtime --projectName minimal-demo --packageName group.zn.demo --outputDir target/minimal-demo
./mvnw -f target/minimal-demo/pom.xml test
./mvnw -f target/minimal-demo/pom.xml exec:java
./mvnw -f target/minimal-demo/pom.xml -Dexec.mainClass=group.zn.demo.RuntimeAssembly exec:java
```

Windows 使用 `mvnw.cmd`。纯 runtime 默认仅装配配置和执行器；业务模板仍按业务需要安装 Actor、日志和监控。诊断入口 `RuntimeAssembly.main` 适用于所有模板，不启动业务、线程池或外部客户端。它输出当前 profile、缺失配置键和完整时的实际组件计划；本地 demo 运行后自行关闭。

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
java scripts/NewLocalGame.java --catalog examples/scaffold-catalog/greeting.catalog.json --template runtime --components greeting --projectName greeting-demo --packageName group.zn.greeting --outputDir target/greeting-demo
./mvnw -f target/greeting-demo/pom.xml test exec:java
./mvnw -f target/greeting-demo/pom.xml -Dexec.mainClass=group.zn.greeting.RuntimeAssembly exec:java
```

示例会注册 `application.greeting` 并提供 `sample.greeting` 字符串能力，生成项目的测试将实际装配图与 manifest 对照。它没有第三方依赖，方便在最小环境中验证扩展路径。

JSON 的 `schemaVersion` 固定为 `1`，`id` 是应用自己的版本化目录身份（例：`example.greeting.v1`）。`capabilities` 声明 API 坐标、基数和依赖，`providers` 声明实现坐标、提供/需要的能力和 profile，`components` 声明 Provider ID、安装表达式、默认配置和配置样例。完整格式见 [示例文件](../../examples/scaffold-catalog/greeting.catalog.json)。未知字段、未知 ID、重复组件/Provider、循环能力依赖、单值能力冲突和不支持的 profile 会在生成前失败。

JSON 扩展是对标准目录的追加；不会自动替换内置项。第三方安装表达式必须来自可信应用代码：生成器只写入表达式，不加载类，但生成项目运行时会执行它。所有 Maven 坐标当前使用生成项目的 `${zero.version}`；第三方版本不同需在应用 POM 明确调整。需要整体替换目录时，可使用 `ScaffoldComponents(id, model, definitions)` Java API。

生成的 `zero-scaffold.json` 记录 `catalogId` 和 `catalogSchemaVersion`。升级时传入相同 `--catalog` 及组件参数，例如将上述生成命令追加 `--plan`，审阅后使用 `--apply`。Java API 对应 `new ScaffoldUpgradeService(components)`。使用不同目录升级会明确报 `SCAFFOLD-CATALOG-MISMATCH`；有用户修改冲突时仍不覆盖。
