# zeroServer Production Adapter fail-fast 契约

```text
production-adapter-failfast-contract=minimum-slice-implemented|productionReady=false
```

本文记录 `production-adapter-failfast-contract` 的启动期最小契约与现有实现。`minimum-slice-implemented` 只表示本文列出的启动期行为已有代码和测试覆盖，不是生产就绪声明。

PAF1 只解决显式选择 production runtime 后的严格选择、缺配置 fail-fast、一次性启动健康、累计启动预算、安全失败归因和资源回收。local runtime 继续默认无外部中间件依赖。

## 1. 覆盖范围与入口

PAF1 覆盖六个外部 Adapter 槽位；production network 使用同一显式组件图，但不连接外部中间件：

| 稳定 Adapter 名称 | 能力 | 实现模块 |
| --- | --- | --- |
| `kafka-rpc` | Kafka RPC transport 与 handler registry | `zero-rpc-kafka` |
| `mongo-data` | MongoDB data | `zero-data-mongo` |
| `redis-data` | Redis data | `zero-data-redis` |
| `redis-cache` | Redis L2 cache | `zero-data-redis` |
| `postgresql-data` | PostgreSQL data | `zero-data-postgresql` |
| `nacos-discovery` | Nacos discovery 与 RPC service resolver | `zero-discovery-nacos` |
| `network-lifecycle` | 连接准入、鉴权、心跳、重连和有界限流组合 | `zero-net` |

显式运行时入口位于 `zero-server-starter-production`：

- `ZeroProductionRuntimeFactory.productionBuilder(...)` 只接受 `production` profile。
- `ZeroProductionRuntimeFactory.externalTestBuilder(...)` 只接受 `external-test` profile。
- `ZeroProductionRuntimeBuilder.diagnose()` 只解析配置并生成安全报告，不创建真实 Adapter。
- `ZeroProductionRuntimeBuilder.build()` 校验配置、创建构建期资源并返回未启动的 `ZeroProductionRuntime`。
- `ZeroProductionRuntime` 直接实现 `GameRuntime`；中立图使用 `report()`，Adapter 诊断使用 `productionReport()`。
- `LocalRuntime.create(...)` 与 `LocalRuntime.builder(...)` 不扫描 classpath，也不会因为引入真实 Adapter 模块而连接 Kafka、MongoDB、Redis、PostgreSQL 或 Nacos。

全部 Adapter disabled 且 discovery 使用默认 `local` 是合法组合。显式启用任一 Adapter 后，缺配置、非法配置、客户端创建、启动、注册、启动健康或启动预算失败都会阻止启动；不允许静默切换到本地实现，也不进入 degraded 状态。

1D 已将 Kafka RPC、MongoDB data、Redis data/cache、PostgreSQL、Nacos discovery/resolver 与 production network 迁移为正式 runtime provider。外部 client 在取得后立即进入中立 build resource ledger；Redis data/cache 共享一个包内资源 handle，且不公开 `RedisClient`。外部 Adapter 各自执行 mandatory startup health；network provider 只组合本地策略、遥测和受管执行器，不声明虚假的外部健康探针。业务统一通过中立 typed capability 访问，不再从 runtime 读取驱动对象。

## 2. 严格选择器

以下 enabled 键缺失表示 disabled；键存在时只接受精确小写 `true` 或 `false`：

```properties
zero.adapter.rpc.kafka.enabled=true|false
zero.adapter.data.mongo.enabled=true|false
zero.adapter.data.redis.enabled=true|false
zero.adapter.cache.redis.enabled=true|false
zero.adapter.data.postgresql.enabled=true|false
```

解析不执行 trim，也不忽略大小写。下列值全部是 `CONFIG_SELECTION / CONFIG_INVALID`：

```text
tru
TRUE
yes
1
<空字符串>
<纯空白>
 true
```

Nacos 使用独立的严格选择器：

```properties
zero.discovery.mode=local|nacos
```

- 键缺失时使用 `local`。
- 键存在时只接受精确小写 `local` 或 `nacos`。
- 未知值、大小写变体、空字符串、纯空白和带前后空格的值全部立即 fail-fast。

PAF1 不维护旧 selector 兼容层。production network 的 `zero.net.lifecycle.enabled` 保留其既有解析边界，不属于本切片的 Adapter selector 破坏性收敛范围。

Network enabled 继续使用 `Boolean.parseBoolean` 语义；只有结果为 true 且 builder 显式提供 `networkPolicy(...)` 时才选择 provider。默认 builder 的 direct remote IO executor 会被拒绝，调用方必须提供不会内联的受管执行器。自定义 `networkRateLimiter(...)` 时，默认三项限流参数保持不消费，延续旧入口语义。

## 3. Production 必填隔离配置

只有显式启用相应槽位后，下列字段才成为必填项；disabled 槽位不会要求其配置：

| Adapter | 必填隔离配置 | 正式入口 |
| --- | --- | --- |
| Kafka RPC | bootstrap servers | `zero.rpc.kafka.bootstrap-servers` 或 `ZERO_KAFKA_BOOTSTRAP_SERVERS` |
| Kafka RPC | client id | `zero.rpc.kafka.client-id` |
| Kafka RPC | consumer group | `zero.rpc.kafka.consumer-group-id` |
| Kafka RPC | topic prefix | `zero.rpc.kafka.topic-prefix` |
| Kafka RPC | reply topic | `zero.rpc.kafka.reply-topic` |
| MongoDB data | URI | `zero.mongo.uri` 或 `ZERO_MONGO_URI` |
| MongoDB data | database | `zero.mongo.database` 或 `ZERO_MONGO_DATABASE` |
| Redis data | URI | `zero.redis.uri` 或 `ZERO_REDIS_URI` |
| Redis cache | URI | `zero.redis.uri` 或 `ZERO_REDIS_URI` |
| Redis cache | namespace | `zero.adapter.cache.redis.namespace` |
| Redis cache | cache name | `zero.adapter.cache.redis.cache-name` |
| Redis cache | 业务 value codec | `ZeroProductionRuntimeBuilder.redisCacheValueCodec(...)` |
| PostgreSQL data | JDBC URL | `zero.postgresql.url` 或 `ZERO_POSTGRESQL_URL` |
| PostgreSQL data | username | `zero.postgresql.username` 或 `ZERO_POSTGRES_USER` |
| PostgreSQL data | password | `zero.postgresql.password` 或 `ZERO_POSTGRES_PASSWORD` |
| PostgreSQL data | table name | `zero.postgresql.table` 或 `ZERO_POSTGRESQL_TABLE` |
| Nacos discovery | server address | `zero.discovery.nacos.server-addr`、`zero.nacos.serverAddr` 或 `ZERO_NACOS_SERVER_ADDR` |
| Nacos discovery | namespace | `zero.discovery.nacos.namespace`、`zero.nacos.namespace` 或 `ZERO_NACOS_NAMESPACE` |
| Nacos discovery | default group | `zero.discovery.nacos.default-group`、`zero.nacos.defaultGroup` 或 `ZERO_NACOS_DEFAULT_GROUP` |
| Nacos discovery | default cluster | `zero.discovery.nacos.default-cluster`、`zero.nacos.defaultCluster` 或 `ZERO_NACOS_DEFAULT_CLUSTER` |

配置来源优先级是 `ZeroConfig -> JVM system property -> environment`。某个高优先级候选不存在时才会继续查找；候选已经存在但为空白时立即失败，不回退到低优先级来源。诊断只记录逻辑键、来源类型和来源键，不记录值。

URI、JDBC URL、host、database、topic、namespace、group、username、password 和认证属性无论实际内容如何，都按敏感业务原值处理。旧 production starter 键 `zero.kafka.bootstrapServers` 已移除且不会作为 fallback；迁移后使用 `zero.rpc.kafka.bootstrap-servers`，或通过 `ZERO_KAFKA_BOOTSTRAP_SERVERS` 注入。

Kafka 的 `pending-capacity`、`poll-timeout-millis`、`close-timeout-millis` 以及 Nacos 的 request timeout 等非必填数值仍有框架默认值，但已存在的值必须是可解析的正整数；非法值只公开配置键名。

## 4. Kafka 安全公共属性

业务只能通过以下入口把最小安全属性同时交给 Kafka producer、consumer 与 startup Admin health：

```java
builder.kafkaClientProperties(properties);
```

白名单严格为：

```text
security.protocol
ssl.*
sasl.*
```

其他键立即以 `CONFIG_VALIDATION / CONFIG_INVALID` 拒绝。业务不能通过该入口覆盖框架管理的 bootstrap servers、client id、consumer group、serializer、deserializer、request timeout、default API timeout 或 max-block timeout。

属性 Map 会被复制，不进入装配报告、日志或 settings 文本。`KafkaRpcSettings.toString()` 以及 MongoDB、Redis、PostgreSQL、Nacos settings 的公开文本均不得回显原值。Kafka transport telemetry 由 production starter 把 `RpcTransportObserver` 接入 `LogAppender`；`zero-rpc-kafka` 不反向依赖 `zero-log`。该桥只写固定安全消息及低基数字段 `eventType`、`result`、`transportType=kafka`，不复制 transport name、correlationId、service、method、topic、group、message 或任意 attributes。

## 5. 强制 startup health

production builder 不再公开 `healthChecks(false)` 或等价绕过能力。每个 enabled Adapter 都必须经过一次启动健康阶段：

| Adapter | startup health 边界 |
| --- | --- |
| Kafka RPC | Admin client 读取 broker cluster metadata；不创建 topic，不发送业务消息 |
| MongoDB data | 在当前剩余预算内创建临时 client 并执行 Mongo health check |
| Redis data | 在当前剩余预算内创建临时 client 并执行 data health check |
| Redis cache | 在当前剩余预算内创建临时 client 并执行 cache health check |
| PostgreSQL data | 使用受预算约束的 JDBC connection / validation 检查 |
| Nacos discovery | 启动 discovery 后验证生命周期已进入 running |

健康成功把诊断推进为 `HEALTHY`；失败把诊断原子推进为 `FAILED` 并阻止 runtime 启动。测试隔离只能使用包级 `ProductionHealthProbe` seam，不恢复公共 bypass。

PAF1 只实现一次性 startup health，不实现周期健康、readiness/liveness 完整分离、自动恢复、retry、degraded 或熔断状态机。

## 6. 总预算、单 Adapter timeout 与驱动原生 timeout

启动预算配置为：

| 配置键 | 默认值 | 约束 |
| --- | ---: | --- |
| `zero.adapter.startup-budget-millis` | `60000` | 全部 Production Adapter 串行启动的累计正数预算 |
| `zero.adapter.startup-timeout-millis` | `10000` | 单 Adapter 最大正数 timeout，且必须小于或等于累计预算 |

`ZeroProductionRuntime` 第一次 start 时固定 Production 累计启动预算起点，重复调用不会重置。中立 runtime 也在第一次 `GameRuntime.start()` 时创建独立 startup deadline；它与 planning/config/create 使用的 assembly deadline 不共享计时起点。资源创建耗时不会计入 `zero.adapter.startup-budget-millis`，该配置只约束串行 lifecycle start 与 startup health。

每个真实 Adapter start 和每个 startup health probe 进入前都会读取共享剩余启动预算，得到的本阶段预算不超过单 Adapter 上限。预算耗尽使用 `STARTUP_BUDGET / STARTUP_BUDGET_EXHAUSTED`，不会调用后续驱动阶段。assembly/startup 两个阶段仍复用既有稳定超时 ErrorCode，并通过失败 phase 区分，不改变现有 Production Adapter ErrorCode 集合。

驱动边界按当前能力设置原生 timeout：

- Kafka producer/consumer/Admin 使用 framework 强制的 request、max-block 或 default API timeout；Admin 查询与关闭共享一次健康检查的绝对 deadline。
- MongoDB 使用 server selection、connect 和 read timeout。
- Redis 使用 connection、socket 和 blocking socket timeout，同时保留 URI 中的 endpoint、认证、database、protocol 与 SSL 语义。
- PostgreSQL 使用连接独立 properties 的 connect/socket timeout 和 `Connection.isValid`；不修改全局 `DriverManager` login timeout。JDBC 只能可靠表达整秒 timeout，因此单 Adapter 配置小于一秒时在进入驱动前 fail-fast。
- Nacos request timeout 受单 Adapter 上限约束。

该模型不会创建临时线程池，也不引入新线程模型。它能证明“驱动原生 timeout + 阶段前累计预算检查”，不能证明第三方驱动忽略 timeout 或 interrupt 时仍可被硬 wall-clock 取消。严格 wall-clock 隔离需要后续受管 remote-IO 执行域切片。

## 7. 状态、阶段与真实 ErrorCode

公开诊断类型由 `ZeroProductionAdapterState`、`ZeroProductionAdapterStatus`、`ProductionAdapterFailurePhase`、`ProductionAdapterErrorCode` 与 `ProductionAdapterException` 组成；调用方应依赖这些稳定类型，而不是解析异常文本。

### 7.1 Adapter 状态

`ZeroProductionAdapterStatus` 是不可变快照；内部 diagnostic 以完整同步快照更新，避免并发报告观察到 state、phase 和 ErrorCode 撕裂。

| 状态 | 含义 |
| --- | --- |
| `DISABLED` | 槽位未启用 |
| `ENABLED` | 已启用且必填配置解析完成，尚未创建组件 |
| `MISSING_CONFIG` | 已启用但缺少必填项；`build()` 会 fail-fast |
| `CREATED` | 组件已创建或已进入延迟创建计划 |
| `STARTED` | Adapter 生命周期已启动 |
| `HEALTHY` | 强制 startup health 已通过 |
| `FAILED` | 创建、启动、健康或关闭阶段失败；必须同时携带 phase、真实 ErrorCode 和固定安全消息 |

### 7.2 失败阶段

公开失败阶段只有：

```text
CONFIG_SELECTION
CONFIG_VALIDATION
CLIENT_CREATION
CONNECT
AUTHENTICATION
STARTUP
REGISTRATION
STARTUP_HEALTH
STARTUP_BUDGET
ROLLBACK
CLOSE
```

`NONE` 只用于非失败状态。框架只使用能够从自身边界可靠归因的阶段，不解析第三方异常文本猜测连接地址、账号、鉴权原因或业务对象；无法可信细分的驱动错误保持在 `CLIENT_CREATION`、`STARTUP` 或 `STARTUP_HEALTH` 等框架阶段。

### 7.3 ErrorCode

| 枚举 | 稳定 code |
| --- | --- |
| `CONFIG_INVALID` | `ZERO-PRODUCTION-ADAPTER-CONFIG-INVALID` |
| `CONFIG_MISSING` | `ZERO-PRODUCTION-ADAPTER-CONFIG-MISSING` |
| `CLIENT_CREATION_FAILED` | `ZERO-PRODUCTION-ADAPTER-CLIENT-CREATION-FAILED` |
| `CONNECTION_FAILED` | `ZERO-PRODUCTION-ADAPTER-CONNECTION-FAILED` |
| `AUTHENTICATION_FAILED` | `ZERO-PRODUCTION-ADAPTER-AUTHENTICATION-FAILED` |
| `STARTUP_FAILED` | `ZERO-PRODUCTION-ADAPTER-STARTUP-FAILED` |
| `REGISTRATION_FAILED` | `ZERO-PRODUCTION-ADAPTER-REGISTRATION-FAILED` |
| `STARTUP_HEALTH_FAILED` | `ZERO-PRODUCTION-ADAPTER-STARTUP-HEALTH-FAILED` |
| `STARTUP_BUDGET_EXHAUSTED` | `ZERO-PRODUCTION-ADAPTER-STARTUP-BUDGET-EXHAUSTED` |
| `RUNTIME_REUSE_REJECTED` | `ZERO-PRODUCTION-RUNTIME-REUSE-REJECTED` |
| `ROLLBACK_FAILED` | `ZERO-PRODUCTION-ADAPTER-ROLLBACK-FAILED` |
| `CLOSE_FAILED` | `ZERO-PRODUCTION-ADAPTER-CLOSE-FAILED` |

report 保存的是实际 `ErrorCode` 对象，不是临时字符串或伪错误码。对外异常统一为不保留第三方 Throwable 图的 `ProductionAdapterException`。

## 8. 安全异常图与诊断 allowlist

允许公开的诊断信息只有：

- 稳定 Adapter 名称。
- runtime profile、Adapter state、failure phase 和真实 ErrorCode。
- 逻辑配置键、source type、source key 和 sensitive 标记。
- 框架组件类型。
- 固定安全 message；缺配置或非法配置时可包含键名，不包含值。
- 不含业务原值的阶段耗时；PAF1 当前没有把 Adapter 阶段耗时加入公开 report，后续若增加仍受本 allowlist 约束。

禁止在 report、日志、异常 message、cause、suppressed、stack trace 或 `toString()` 中保存或输出：

- 任意配置值。
- host、IP、database、topic、namespace、group 的原值。
- username、password、token、secret、access key 或认证 header。
- 完整 URI、JDBC URL 或其他连接串。
- 第三方异常 message、第三方异常类名或第三方原始 Throwable 图。
- secret-bearing settings/record 的默认 `toString()` 文本。

“启动异常保持 primary”指经过安全转换后的框架主异常保持 primary。原始驱动 Throwable 不会成为 cause 或 suppressed；rollback/close 失败必须先转换为 `ProductionAdapterException`，再按实际发生顺序追加为安全 suppressed。安全异常图有深度限制和循环检测。

## 9. 构建、启动、停止与回滚

### 9.1 Build 事务

每个 provider 在 client 创建成功后立即调用自身的中立 `ResourceRegistrar`。`RuntimeBuildTransaction` 把资源写入统一 `BuildResourceLedger`；构建中途失败时：

1. 把原失败转换为安全 primary。
2. 严格按创建顺序逆序关闭全部已登记资源。
3. 任一关闭失败不阻断后续关闭。
4. 每个关闭失败以 `ROLLBACK / ROLLBACK_FAILED` 安全 suppressed 附加到 primary。

### 9.2 Start 回滚

中立 `GameRuntime` 只记录真正成功完成 `start()` 的生命周期组件。某组件启动失败时：

1. 失败组件和尚未尝试的组件不作为“已成功启动组件”重复 stop。
2. 已成功组件按实际启动顺序严格逆序 stop。
3. 每个 rollback 失败继续聚合到原启动异常的 suppressed。
4. runtime 拥有的 executors 在组件回滚后关闭；执行器关闭失败同样作为 suppressed。
5. 同一个中立 runtime ledger 继续逆序关闭尚未释放的 build 资源，包括尚未进入 start 的 client；`ZeroProductionRuntime` 只负责安全归因，不维护第二份资源清单。

### 9.3 Stop / close

正常 stop 时，生命周期组件严格逆序、best-effort 停止，executors 最后关闭；已有失败不会阻断后续组件。随后 build 资源按创建顺序严格逆序关闭。

每个 build 资源只有在底层 close 真正成功后才标记为已关闭。后续 `close()` 会跳过已成功资源，只重试此前失败资源；多个失败以第一项为安全 primary，其余按发生顺序成为安全 suppressed。

Kafka 构造、handler replay、timeout wheel、request/reply subscription 和 health 任一阶段失败都会回收已经创建的子资源；多个 subscription 先统一 wakeup，并共享一个绝对关闭 deadline，避免每个 consumer 独占完整 timeout。Nacos 保持启动主失败为 primary，shutdown 失败只作安全 suppressed；stop 按 unsubscribe、deregister、shutdown 全部 best-effort，unsubscribe 失败保留可重试状态，重复 stop/close 幂等。

## 10. Runtime single-use 契约

`ZeroProductionRuntime` 是 single-use：

- 第一次真实启动尝试会永久占用唯一启动机会；启动失败后，或成功运行后再 stop/close，都不能重新执行同一个对象的启动逻辑。
- runtime 已处于 `RUNNING` 时再次调用 `start()` 也会明确拒绝，不会被通用生命周期幂等分支吞掉。
- 在 start 前调用 `close()` 也会终止该对象，之后不得 start。
- start 后 stop/close 的对象不得再次 start。
- 重用以 `STARTUP / RUNTIME_REUSE_REJECTED` fail-fast。
- single-use 不妨碍补偿关闭重试；关闭失败后再次 `close()` 只重试未成功释放的 build 资源。

需要重新启动时必须重新创建 builder/runtime，让配置解析、预算起点、资源所有权和诊断状态都形成新的事务边界。

## 11. external-tests 与 Docker gate

外部验证只通过以下 profile 显式进入：

```powershell
mvn -Pexternal-tests verify
```

Maven gate 约束为：

- 默认 Reactor 不绑定 Failsafe external tests。
- `-Pintegration-tests` 明确排除 `**/*ExternalIT.java` 与 `**/*ExternalIntegrationTest.java`。
- `-Pexternal-tests` 只包含上述 External 测试命名，并注入 `zero.external.tests=true`。
- Docker 不进入 CI，不作为 local runtime 的启动前提。

运行真实组件前，应准备与个人数据隔离的本地或 CI 环境，并记录组件版本、主机端口、非秘密配置来源、数据目录和清理方式。Kafka、MongoDB、Redis、PostgreSQL 与 Nacos 的真实环境测试只由 `-Pexternal-tests` 显式启用，默认 CI 不启动 Docker；Pull Request 应明确写出实际运行的套件、结果、未验证项和剩余风险。

## 12. PAF1 验收映射

| 编号 | 契约 | 主要实现落点 |
| --- | --- | --- |
| PAF1-01 | 五个 enabled 键严格解析 | `ProductionConfigResolver.strictEnabled` |
| PAF1-02 | Nacos mode 缺失为 local，非法值 fail-fast | `ProductionConfigResolver.strictChoice` |
| PAF1-03 | 缺配置只公开键名 | `ProductionConfigResolver.required`、`ProductionAdapterFailures` |
| PAF1-04 | report/异常图/settings 文本通过 secret sentinel 反证 | 安全 report、exception factory 与各 Adapter settings |
| PAF1-05 | build 中途失败逆序关闭已登记资源 | `RuntimeBuildTransaction`、`BuildResourceLedger` |
| PAF1-06 | runtime 启动失败关闭尚未 start 的 build 资源 | `GameRuntime` build resource ledger |
| PAF1-07 | 多个 rollback/close 失败全部安全聚合且继续关闭 | `GameRuntime`；Production 门面只转换安全归因 |
| PAF1-08 | Kafka 分阶段失败完整回滚 | `KafkaRpcAdapter`、`KafkaRpcLifecycleAdapter` |
| PAF1-09 | Kafka 多 subscription 共享绝对关闭 deadline | `KafkaRpcCloseDeadline` 与 consumer 关闭路径 |
| PAF1-10 | Nacos 主失败、shutdown suppressed 与 unsubscribe 重试 | `NacosDiscoveryAdapter` |
| PAF1-11 | 驱动原生 timeout 受累计预算约束 | `ProductionStartupBudget` 与各 driver factory/health check |
| PAF1-12 | Kafka producer/consumer/Admin 使用一致安全属性 | `kafkaClientProperties`、`KafkaClusterHealthCheck` |
| PAF1-13 | local runtime 默认不连接外部组件 | `LocalRuntime` 与独立 production starter |
| PAF1-14 | external tests 仅由显式 profile 启用 | `zero-parent` Failsafe profiles |

上述映射用于描述应覆盖的行为，不替代持续集成结果。提交相关变更时应重新执行根 Reactor 默认测试和 quality profile；涉及真实 Kafka、MongoDB、Redis、PostgreSQL 或 Nacos 的行为，还应在隔离环境中显式运行 external-tests，并在 Pull Request 中记录组件版本、配置来源与结果。

## 13. 明确不证明的能力

PAF1 不证明：

- 严格 wall-clock 强制取消。
- 周期健康、自动恢复、重试、degraded、熔断、故障转移或完整重平衡。
- PostgreSQL DataSource/连接池、Redis journal 提交状态、Repository/DataService 自动注册或 Nacos 自动路由注入。
- 容量、尾延迟、长稳、故障演练、SLA、生产安全全闭环或 CI 性能阈值。
- 单机 Docker external-tests 不证明多节点生产拓扑、网络分区、故障恢复、容量、长稳或 SLA。
- 生产就绪或长期框架目标已完成。

当前结论始终保持：

```text
productionReady=false
longTermGoalComplete=false
```
