# CSV 配置加载与本地原子热重载

状态：`minimum-slice / candidate-public-api / productionReady=false`

本能力面向单进程原型和本地开发，提供 UTF-8 CSV 游戏配置表的 typed 加载、完整校验、不可变快照、运行期单表原子替换、失败保旧和审计日志。首轮实现位于 `zero-hot-update/config`，由 `zero-server-starter` 显式 opt-in 组合，不新增 Maven 模块，也不改变现有依赖方向。

## 1. 数据流与发布语义

```text
CSV 文件
  -> Apache Commons CSV RFC 4180 解析
  -> 显式 key decoder + 泛型 row decoder
  -> 必需列 / 列数 / 空 key / 重复 key 校验
  -> 业务 ConfigTableValidator
  -> LinkedHashMap 有序候选
  -> 不可变 ConfigTableSnapshot
  -> AtomicReference 发布
```

初始启动与运行期语义不同：

```text
initial start
  -> 依次构建所有注册表候选
  -> 任一候选失败：启动失败，所有表均不发布
  -> 全部成功：发布全部初始快照
  -> 可选启动 WatchService

single-table reload
  -> 读取完整新文件并计算 SHA-256
  -> 摘要相同：NO_CHANGE，版本不增加
  -> 解析 / decode / validator 失败：REJECTED，保留旧快照
  -> 候选成功：单次 AtomicReference 替换，版本增加
```

“全部初始候选验证后发布”用于避免一张表解析失败时其他表已经成为可用业务状态；运行中只保证单表原子替换，不提供多文件事务或跨节点一致性。

## 2. CSV 契约

- 编码固定为 UTF-8，允许文件开头存在 BOM。
- 语法采用 RFC 4180，支持引号内逗号、CRLF 和引号内换行。
- 第一行固定为表头，重复表头拒绝。
- 每张表显式声明稳定表名、文件、key 列、必需列、key decoder、row decoder 和 validator。
- 缺少必需列、数据列数与表头不一致、空 key、重复 key、非法 CSV、decoder 失败或 validator 失败时整表拒绝。
- 第一轮不提供反射注解映射、Excel/JSON/YAML、DTO codegen 或增量原地修改。

## 3. 最小业务接入

```java
ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("my-game", 4);
ZeroRuntimeBuilder builder = ZeroRuntimeFactory.localBuilder(config, logSink, executors);
LocalConfigHotReloadService configService = ZeroConfigHotReloadFactory
        .configure(builder, config, logSink, executors)
        .orElseThrow();

ConfigTable<Integer, ItemConfig> items = configService.register(ConfigTableDefinition.of(
        "items",
        configDirectory.resolve("items.csv"),
        "id",
        Set.of("id", "name", "price"),
        Integer::valueOf,
        row -> new ItemConfig(
                Integer.parseInt(row.require("id")),
                row.require("name"),
                Integer.parseInt(row.require("price")))));

ZeroRuntimeComponents components = builder.build();
components.start();

ItemConfig item = items.find(1001).orElseThrow();
ConfigTableSnapshot<Integer, ItemConfig> snapshot = items.snapshot();
```

如果一次业务逻辑需要读取多个 key，应先保存一次 `snapshot()`，确保本次计算只使用同一表版本。`snapshot.values()` 不可修改且保持 CSV 行顺序；`find(key)` 是 O(1) 内存查询，不执行文件 IO，也不等待 reload。

显式手工重载需要提供请求上下文：

```java
CompletionStage<ConfigReloadResult> stage = configService.reloadAsync(
        "items",
        new HotUpdateRequest(
                "config:items",
                HotUpdateLevel.SEAMLESS,
                "2",
                operator,
                traceId,
                Instant.now()));
```

非法候选正常完成为 `REJECTED`，并在结果中绑定具体 `ConfigReloadErrorCode`；executor 或 observer 故障会异常完成，不会被静默吞掉。

## 4. Starter 配置

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `zero.config.hot-reload.enabled` | `false` | 总开关；只有显式为 `true` 才创建并挂载服务。 |
| `zero.config.hot-reload.watch-enabled` | `false` | 是否启用本地 `WatchService`。 |
| `zero.config.hot-reload.debounce-ms` | `500` | 文件事件静默去抖窗口，必须为正整数。 |
| `zero.config.hot-reload.stop-timeout-ms` | `3000` | 关闭 watcher 后等待受管任务退出的上限，必须为正整数。 |
| `zero.config.hot-reload.operator` | `local-config` | 初始加载和 watcher 自动请求使用的审计操作者。 |

布尔值只接受忽略大小写的 `true/false`；非法值、空白 operator、非正时间均 fail-fast，并绑定 `ZERO-CONFIG-INVALID-OPTIONS`。

启用服务时必须使用 `ZeroRuntimeExecutors.localPrototype(...)` 或业务项目提供的受管非内联 remote IO executor。Starter 工厂会拒绝 `ZeroRuntimeExecutors.direct()`：即使关闭 watcher，CSV 读取、SHA-256、decode 和 validator 也不应在 Actor、Netty IO 或启动调用线程内联执行。

## 5. WatchService 边界

- watcher 默认关闭，适用于需要部署系统控制文件变更的场景。
- 监听 create、modify 和 delete；同一静默窗口内按表名去重，并按注册顺序重载。
- JDK 报告 `OVERFLOW` 时，重新加载该目录内全部受管表。
- `WatchService.take()` 是长期阻塞 IO，只能占用 remote IO 执行域，不能占用单线程 background executor。
- `stop()` 先关闭 `WatchService` 解除阻塞，再等待 watcher 任务退出；服务不创建或关闭注入 executor。
- watcher 回调只处理配置元数据和快照，不得直接跨线程修改 player/scene Actor 状态。需要响应配置变化时，业务必须向所属 Actor 投递消息。

## 6. 快照、版本与并发

- 每张表使用独立、本地、单调递增版本；初始版本为 1。
- SHA-256 基于原始文件字节；字节完全相同时返回 `NO_CHANGE`，不增加版本，也不替换快照对象。
- `ConfigTableSnapshot` 复制 `LinkedHashMap` 后暴露只读视图，保持 CSV 行顺序。
- `ConfigTable` 通过 `AtomicReference` 发布完整快照；并发读者只能看到完整旧版本或完整新版本。
- reload 路径按单表同步，避免同表并发加载发生版本竞争；不同表不承诺事务关系。
- 候选构建期间同时持有旧、新两份表数据；首轮不承诺超大 CSV 的峰值内存、GC 或加载 SLA。

## 7. 审计、日志与 ErrorCode

`LoggingConfigReloadObserver` 对 `LOADED`、`NO_CHANGE` 和 `REJECTED` 写审计日志；拒绝结果额外写绑定 ErrorCode 的错误日志。允许字段包括：

- `traceId`、operator、请求版本。
- 稳定表名与脱敏文件名，不记录完整路径。
- 旧版本、新版本、SHA-256、行数、结果和耗时。
- 拒绝时的 `ConfigReloadErrorCode`。

日志禁止包含 CSV 行、配置对象、完整路径、token、密码、access key 或 secret key。主要错误码覆盖定义非法、运行参数非法、文件读取、CSV 解析、必需列、空/重复 key、key/row decode、validator、生命周期、executor、watcher 和 observer 故障。

## 8. 示例、脚手架与验证入口

- 可运行示例：`examples/config-hot-reload-local`。
- 可复制模板片段：`templates/config-hot-reload-snippet`。
- 核心 focused tests：`CsvConfigHotReloadFocusedTest`、`LocalConfigFileWatcherTest`。
- Starter focused test：`ZeroConfigHotReloadFactoryTest`。

```powershell
mvn -pl zero-hot-update,zero-server-starter -am test
mvn -DskipTests install
mvn -f examples/config-hot-reload-local/pom.xml test
mvn -f examples/config-hot-reload-local/pom.xml exec:java
```

focused tests 覆盖 BOM/RFC 4180、结构错误、重复 key、decoder/validator、初始零发布、合法替换、失败保旧、SHA no-op、并发快照、watcher 去抖、OVERFLOW 路由、关闭解阻塞、线程边界和敏感日志隔离。

## 9. 当前非目标

- 不接入 Nacos 配置通知、Kafka 广播或集群版本同步。
- 不提供运行中多文件事务、跨节点一致性、版本仲裁或回滚历史库。
- 不实现 CGLIB 修复、ClassLoader 插件化或核心代码热更。
- 不实现远程上传、签名、RBAC、IP 白名单、审批流或完整配置后台。
- 不冻结 1.0 稳定 API；本轮公共类型仍是 0.x 候选 API。
- 不声明 production ready；生产使用仍需容量、长稳、文件发布原子性、部署权限和集群策略专项验证。
