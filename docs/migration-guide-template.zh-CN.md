# zeroServer 迁移说明模板

```text
zero-migration-guide-template=template|sections=9|breakingChanges=true|rollback=true|migrationResult=false
```

> 本文件是可复用结构，不是任何具体版本的迁移结果。准备含行为变化或破坏性变化的版本时，请复制为独立文档并填写所有适用项；保留“不适用”的理由，不要静默删除风险章节。

本模板已内置 PAF1 在 0.1 阶段的破坏性迁移检查项。只有目标版本包含 `production-adapter-failfast-contract` 时才适用；复制后仍必须填写负责人、环境、实际命令、验证证据与回滚决策。PAF1 minimum slice 已完成并归档，但本模板本身不替代该归档证据，也不表示 Docker external tests 已运行或某个发布迁移已经完成。

## 1. 版本与责任信息

- 来源版本：`<填写，例如 0.x.a>`
- 目标版本：`<填写，例如 0.x.b>`
- 适用模块：`<填写>`
- 适用部署形态：`单进程 / 分布式 / 两者`
- 迁移负责人：`<填写>`
- 复核人：`<填写>`
- 关联任务 / Issue / PR：`<填写>`
- 计划迁移窗口与时区：`<填写>`

## 2. 变更摘要

用用户视角说明为什么迁移、获得什么能力，以及哪些现有行为会改变。

- 新增能力：`<填写或不适用>`
- 行为变化：`<填写或不适用>`
- 修复：`<填写或不适用>`
- 性能变化：`<填写或不适用>`
- 安全变化：`<填写或不适用>`
- 已知限制：`<填写>`

## 3. 破坏性变更

逐项列出，不要只写“有破坏性变化”。

| 编号 | 影响面 | 旧行为 | 新行为 | 是否可自动迁移 | 风险等级 |
|---|---|---|---|---|---|
| M-01 | `<API / SPI / 协议 / 数据 / 配置 / 日志 / 部署>` | `<填写>` | `<填写>` | `<是 / 否 / 部分>` | `<低 / 中 / 高>` |

### 3.1 PAF1 0.1 破坏性迁移矩阵

目标版本包含 PAF1 时，把下表复制到具体版本迁移说明并结合部署逐项确认。PAF1 不提供旧 production 行为兼容层。

| 编号 | 影响面 | 旧行为 | 新行为 | 迁移动作 | 风险等级 |
|---|---|---|---|---|---|
| PAF1-M01 | production builder API | 可调用 `healthChecks(false)` 跳过启动健康 | public health bypass 已移除；每个 enabled Adapter 强制执行 startup health | 删除 `.healthChecks(false)`；为测试使用包级 probe seam，为真实部署准备可达的外部组件和预算 | 高 |
| PAF1-M02 | Kafka bootstrap 配置 | production starter 可读取旧键 `zero.kafka.bootstrapServers` | 只读取 `zero.rpc.kafka.bootstrap-servers`，或环境变量 `ZERO_KAFKA_BOOTSTRAP_SERVERS` | 重命名 production 配置键；不要把 broker 值写入迁移文档、日志或提交记录 | 高 |
| PAF1-M03 | Adapter selector | 拼写错误、大小写变体、数字/yes、空白或带空格值可能被宽松解析为 false/默认值 | 五个 enabled 键只接受精确小写 `true/false`；`zero.discovery.mode` 只接受精确小写 `local/nacos`；非法值 fail-fast | 盘点并规范所有环境的 selector；不要对值 trim 后继续启动，配置源必须直接提供正式值 | 高 |
| PAF1-M04 | production 必填隔离配置 | 部分 Adapter 使用隐式默认值、随机 client id/group/topic 或共享默认隔离标识 | 显式启用后必须完整提供 Kafka 五项、MongoDB 两项、Redis data URI、Redis cache URI/namespace/cache name/value codec、PostgreSQL 四项、Nacos 四项 | 按 3.2 清单补齐每个 enabled 槽位；disabled 槽位不需要伪造配置 | 高 |
| PAF1-M05 | runtime 生命周期 | 调用方可能尝试 stop 后复用同一 `ZeroProductionRuntime`，或在 start 失败后重试同一对象 | runtime 为 single-use；第一次 start 尝试或提前 close 后再次 start 使用 `RUNTIME_REUSE_REJECTED` fail-fast | 每次启动/重启重新创建 builder 和 runtime；旧对象只用于 stop/close 补偿，关闭失败时再次 close 只重试未释放资源 | 高 |
| PAF1-M06 | 异常与回滚观察 | 调用方可能依赖第三方 cause/message，或只观察第一个 rollback 失败 | 只公开安全 `ProductionAdapterException`、真实 ErrorCode/phase/state；第三方 Throwable 图被移除，多个回滚/关闭失败以安全 suppressed 聚合 | 改为按 ErrorCode、phase、state 和稳定 Adapter 名称分支；不得解析第三方 message、URI、topic、group 或 stack trace 业务值 | 高 |

### 3.2 PAF1 必填配置清单

| Adapter | selector | 启用后必填项 |
|---|---|---|
| Kafka RPC | `zero.adapter.rpc.kafka.enabled=true` | `zero.rpc.kafka.bootstrap-servers`、`zero.rpc.kafka.client-id`、`zero.rpc.kafka.consumer-group-id`、`zero.rpc.kafka.topic-prefix`、`zero.rpc.kafka.reply-topic` |
| MongoDB data | `zero.adapter.data.mongo.enabled=true` | `zero.mongo.uri`、`zero.mongo.database` |
| Redis data | `zero.adapter.data.redis.enabled=true` | `zero.redis.uri` |
| Redis cache | `zero.adapter.cache.redis.enabled=true` | `zero.redis.uri`、`zero.adapter.cache.redis.namespace`、`zero.adapter.cache.redis.cache-name`、`redisCacheValueCodec(...)` |
| PostgreSQL data | `zero.adapter.data.postgresql.enabled=true` | `zero.postgresql.url`、`zero.postgresql.username`、`zero.postgresql.password`、`zero.postgresql.table` |
| Nacos discovery | `zero.discovery.mode=nacos` | `zero.discovery.nacos.server-addr`、`zero.discovery.nacos.namespace`、`zero.discovery.nacos.default-group`、`zero.discovery.nacos.default-cluster` |

敏感项可以来自受控环境变量或本地 ignored 配置，但具体版本迁移说明只能记录键名、来源类型和证据位置，不得复制实际值。Kafka 额外安全属性只能经 builder 白名单传入 `security.protocol`、`ssl.*`、`sasl.*`；不要用该入口覆盖 framework 管理的 bootstrap、client/group、serializer/deserializer 或 timeout。

必须检查：

- Maven 坐标、模块、包结构、公共 API / SPI、注解和代码生成规则。
- 协议 ID、wire format、客户端代码、RPC 字段、超时、幂等和兼容窗口。
- PostgreSQL / MongoDB / Redis 格式、对象映射、索引、缓存 key、版本号和脏数据流程。
- 配置项、默认值、环境变量、端口、topic、服务名和发现 metadata。
- ErrorCode、日志、审计、TraceId、指标、告警和 GM 权限语义。
- 线程 / Actor 归属、队列、背压、定时任务、热更和 ClassLoader 边界。

## 4. 迁移前准备

- [ ] 已阅读 Changelog、release notes 和本迁移说明。
- [ ] 已确认 Java 21、Maven、中间件、客户端和操作系统兼容范围。
- [ ] 已盘点受影响服务、数据量、在线玩家、场景、房间和跨服链路。
- [ ] 已完成配置、数据库、缓存、制品和关键日志备份。
- [ ] 已验证备份可读，并记录恢复负责人和预计恢复时间。
- [ ] 已在隔离环境完成迁移演练。
- [ ] 已定义灰度批次、停机窗口、流量切换和回滚触发条件。

环境与备份证据：`<填写>`

## 5. 迁移步骤

每一步必须包含执行者、前置条件、命令或操作、期望输出、数据变化和失败处理。

1. `<步骤 1>`
   - 前置条件：`<填写>`
   - 执行命令 / 操作：`<填写；不得写入秘密值>`
   - 期望输出：`<填写>`
   - 数据变化：`<填写或无>`
   - 失败处理：`<填写>`
2. `<步骤 2>`
   - 前置条件：`<填写>`
   - 执行命令 / 操作：`<填写>`
   - 期望输出：`<填写>`
   - 数据变化：`<填写或无>`
   - 失败处理：`<填写>`

若包含 schema、索引、缓存或协议迁移，应说明双读 / 双写、批次、幂等、断点续作、校验和限流策略。

### 5.1 PAF1 推荐迁移顺序

1. 复制本模板为具体 0.1 版本迁移说明，登记所有受影响 production/external-test 服务和配置来源，不记录秘密值。
2. 把旧 `zero.kafka.bootstrapServers` 替换为 `zero.rpc.kafka.bootstrap-servers` 或受控环境变量；先删除旧键，避免误以为它仍是 fallback。
3. 把所有 Adapter selector 规范为精确小写正式值；对每个 enabled 槽位补齐 3.2 的必填隔离配置。
4. 删除所有 `.healthChecks(false)`；确认真实 startup health 可访问目标组件，并评估 `zero.adapter.startup-budget-millis` 与 `zero.adapter.startup-timeout-millis`。单 Adapter timeout 必须小于或等于总预算；PostgreSQL 原生 timeout 需要至少一秒。
5. 若使用 Kafka TLS/SASL，把公共安全属性通过 `kafkaClientProperties(...)` 白名单传入，不在文档、命令历史或源码中展开实际 secret。
6. 把“重启同一 runtime”的控制流改为每次重新 build；确保旧 runtime 始终进入 stop/close，并按真实 ErrorCode/phase/state 处理失败。
7. 先验证全部 Adapter disabled 的 local 默认路径，再验证逐槽位的缺配置/非法 selector fail-fast、startup health、预算耗尽、逆序回滚和关闭重试。
8. 只有任务 VERIFY 已按 `M:\docker_space` 规则记录真实容器、端口、数据目录、ignored 凭据来源和清理方式后，才可显式运行 `mvn -Pexternal-tests verify`；Docker 不进入 CI。

PAF1 的预算是驱动原生 timeout 与阶段前累计检查，不是硬 wall-clock 取消保证。迁移验收不得把“期望预算”写成第三方驱动无视 timeout/interrupt 时仍可强制截止的 SLA。

## 6. 验证

| 验证维度 | 方法 / 命令 | 预期 | 实际与证据 |
|---|---|---|---|
| 构建与默认测试 | `<填写>` | `<填写>` | `<填写>` |
| quality / integration / external | `<填写>` | `<填写>` | `<填写>` |
| API / 协议 / 客户端兼容 | `<填写>` | `<填写>` | `<填写>` |
| 数据量、校验和、版本与索引 | `<填写>` | `<填写>` | `<填写>` |
| 缓存命中、失效、击穿与回源 | `<填写>` | `<填写>` | `<填写>` |
| Actor / RPC / 事件 / 定时任务 | `<填写>` | `<填写>` | `<填写>` |
| 日志 / TraceId / 指标 / 告警 / 审计 | `<填写>` | `<填写>` | `<填写>` |
| 关键业务与 GM dry-run | `<填写>` | `<填写>` | `<填写>` |
| 性能、容量、背压与长稳 | `<填写>` | `<填写>` | `<填写>` |

所有未验证项、原因和剩余风险必须进入任务 `VERIFY.md`。

## 7. 回滚

- 回滚触发条件：`<填写可观测阈值和业务条件>`
- 最晚可安全回滚时间：`<填写>`
- 回滚负责人：`<填写>`
- 代码 / 制品回滚：`<填写>`
- 配置回滚：`<填写>`
- 数据 / schema / 索引回滚：`<填写；不可逆时说明向前修复>`
- 缓存 / topic / 服务发现恢复：`<填写>`
- 客户端和协议兼容处理：`<填写>`
- 回滚后验证：`<填写>`
- 审计与事故记录：`<填写>`

禁止在没有确认目标路径、备份和不可逆影响时执行清理或数据回滚。

## 8. 发布后观察

- 观察窗口：`<填写>`
- 值班与升级联系人：`<填写>`
- 重点指标：`<错误率、P95/P99、队列、pending、缓存、flush、连接等>`
- 重点日志 / ErrorCode / 告警：`<填写>`
- 数据一致性抽样：`<填写>`
- 玩家 / 场景 / 房间 / 跨服业务抽样：`<填写>`
- 已知问题与临时降级：`<填写>`
- 结束回滚窗口的批准记录：`<填写>`

## 9. 完成确认

- [ ] 所有适用迁移步骤完成并留有证据。
- [ ] 未适用项有明确理由。
- [ ] 验证与观察没有未处置阻断项。
- [ ] Changelog、release notes、相关文档和任务 `VERIFY.md` 已更新。
- [ ] 回滚窗口结束获得确认。
- [ ] 任务归档完成。

最终结论：`<填写；本模板本身不构成迁移或发布授权>`

<!-- zero-migration-verification-and-rollback=required -->
