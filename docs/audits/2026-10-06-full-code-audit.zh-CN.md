# zeroServer 第二轮全量代码复审报告（Round 2 · 修复后复核版）

复审日期：2026-10-09（初版）；2026-10-10 修复复核重写
复核基线：工作树（相对 1b2bd70 快照的未提交改动，即第二轮修复）
复核方式：6 个并行分区对初版 133 条逐一实读复核 + 协调者交叉验证；全仓主源码与测试源码编译通过（JDK 21 离线 compile + test-compile），8 个新增修复回归测试全部通过。本版**只保留仍未闭环的发现**（未修复/部分修复/有意取舍）与**修复新引入的问题**；已修复的 108 条（含全部 4 个 High 与 Top 6 其余项：CI-1、CI-2、RPC-1、KAFKA-1、PLAYER-1、DATA-3、B-1）已移除，逐条复核证据见会话记录与 git 历史。

> 严重度：Critical=正常运行即可致数据错误/不可用/越权；High=常见触发下数据丢失/永久不可用/安全失效；Medium=特定条件行为错误或显著退化；Low=边角缺陷/维护风险；Info=设计观察项。
>
> 统计：**41 条** —— 未修复 10 / 部分修复 10 / 有意取舍 8（含 Info 2）/ 修复新引入 9 / 已澄清排除 4（见 §5）。

**优先级 Top 5（本轮复核后）**：

1. NEW-1 —— HTTP 守卫 30s 期限硬编码，长轮询/慢业务被强制断连（修复引入，最可能生产回归）
2. AOI-1 —— 等值换引用后观察者永久全量重算（报告内唯一确认的真未修 Medium/Low，一行可修）
3. KCP-1 残余 —— 静默期 FEC 恢复的新 PUSH 段仍杀会话 + 冻结期 progressTimeout 误杀窗口
4. NEW-4 —— WORLD-1/RANK-2 水位用字典序比较，UUID 类幂等键可绕过重放保护（修复引入）
5. NEW-3 —— MigrationRecords 对已退役 ID 抛错误码语义错误，阻断归档历史查询（修复引入）

---

## 1. 未修复（报告原发现仍存在）

### [M] AOI-1 等值引用更新后观察者永久全量重算
`zero-aoi/.../InMemoryAoiIndex.java:135,138-141`、`ObserverState.java:42-49`
- 问题：:135 等值换引用时只置 `stableIdentity=false`，generation 刷新循环只更新 `previous.generation` 不更新 `previous.entity` 引用。
- 影响：equals 但非同一对象的实体更新后，该观察者每次 observe 都全量重算，直到实体真实变更。
- 复核备注：修复为在刷新循环补 `previous.entity = current`，一行可修。

### [L] R-3 HealthProbeRunner 不支持 minimal stage
`zero-runtime/.../HealthProbeRunner.java:58`
- 问题：`toCompletableFuture()` 对 minimalCompletionStage 结果抛 UnsupportedOperationException，被归为 HEALTH_FAILED。
- 影响：第三方探针返回 minimal stage 被误报为健康失败。

### [L] B-2 双层关闭预算独立，总停止可达 2×closeTimeout
`zero-runtime-bootstrap/.../PeriodicAsyncTask.java:71-77`、`ZeroRuntimeExecutors.java`
- 现状：ExecutorShutdown 内部已改单一全局递减预算，但 doStop 独立 closeTimeout 与 executors.close() 仍可叠加；closeTimeout javadoc 已声明语义（文档化取舍，行为未变）。

### [L] KCP-5 claim 路径重复认证
`zero-net-kcp/.../KcpSessions.java:92,95`
- 问题：claim() 先 authenticate 一次（结果未使用），异步 load 后再 authenticate。第二次防 TOCTOU 属防御性，第一次纯冗余。

### [L] LOGIC-1 会话目录无过期/断连回收
`zero-logic/.../LogicSessionManager.java`
- 现状：open 幂等与容量构造器已加，仍无 TTL/断连回收；连接异常断开且上层未回调时滞留至容量满后全局拒绝。

### [L] GM-2 IP 白名单热路径每请求重复解析
`zero-gm/.../GmSourceIpPolicy.java`
- 现状：每次 allows() 仍做 InetAddress.getByName + 流式遍历；可构造期预解析（性能项）。

### [L] GM-3 IN_PROGRESS 幂等记录无租约兜底
`zero-gm/.../InMemoryGmIdempotencyStore.java`、`GmOperationEndpoint.java`
- 现状：Error 路径记账失败后记录永久 IN_PROGRESS 占容量；claim 已传入 ttl 可兼作租约。注释明示为设计选择，风险描述仍成立。

### [L] LOG-3 转义溢出整条日志拒绝
`zero-log/.../LogSafetyGate.java:182`、`LogRecordValidator.java:142-160`
- 现状：字段值尾部含控制字符时 `\uXXXX` 转义膨胀超预算 → 整条 INVALID_RECORD；无截断降级。fail-closed 为有意取舍，「合法值+控制字符定向压制特定业务日志」残余面仍成立。

### [L] DATA-12 RedisCacheStore 弃用构造器默认 commonPool
`zero-data-redis/.../RedisCacheStore.java:131-164`
- 现状：四个 @Deprecated 构造器仍走 ForkJoinPool.commonPool()（COMPATIBILITY_EXECUTOR）；Javadoc 已声明兼容路径，生产装配（含 DATA-3 修复后的 provider）均显式传 ioExecutor。

### [L] DATA-13 残余：LocalDiskDataJournal 读路径静默截断
`zero-data-redis/.../LocalDiskDataJournal.java:141-149`
- 现状：写路径已加文件锁 + 不完整尾 fail-closed（多实例并发写已防护）；但 readAll 读路径遇不完整尾帧仍静默 break，数据截断无日志无告警，与写路径不对称。

---

## 2. 部分修复（核心路径未闭环）

### [M] KCP-1 迁移静默期迟到段杀死会话（残余）
`zero-net-kcp/.../KcpSession.java:126-131,200`、`KcpReceiveTracker.java:36`、`KcpFecCodec.java`
- 已修：可证明已投递的历史重传段获豁免（distance 回绕 + deliveredHistory 判定）。
- 残余：FEC 层恢复的、从未送达过的**新** PUSH 段不经 receiveTracker，静默期仍走 `receive.quiescent_push` 杀会话；关联 :200 冻结期 progressTimeout 误杀窗口（waitSnd 不变时 pendingSince 不重置，`charged>0 && now-pendingSince>=progressTimeout` 仍以 HEARTBEAT_TIMEOUT 杀健康会话）。

### [M] KAFKA-4 响应发送失败无限重建、无 DLQ
`zero-rpc-kafka/.../KafkaRpcAdapter.java:590-610`
- 已修：sendResponse 失败有 lastSendFailure + 事件；KAFKA-5 指数退避（100ms→30s）已限频。
- 残余：仍沿 listener 失败→worker 重建→offset 重投循环；replyTopic 配错/无权限时全部 REQUEST_RESPONSE 消息死循环，无重试上限、无死信。

### [M] DATA-4 框架 ID 不回传（改为 fail-fast，契约变更）
`zero-data/.../ZeroDataEnvelopeCrudRepository.java:274-277`
- 已修方式：null-ID 直接抛 INVALID_ENTITY 防静默分裂，而非回传生成 ID。
- 残余语义：调用方必须用 codec.prepareForSave 先分配 ID；分裂风险消除但属契约收紧，需调用方适配。

### [M] DATA-14 DataRuntime 默认 Direct 绑定执行器（收紧但未根治）
`zero-runtime-data/.../DataRuntime.java:43-95`
- 已修：新增 module(executor)/providers(executor) 注入入口；默认 UnboundSnapshotExecutor 对有绑定实体直接 failedFuture 拒绝（不再静默 Direct）。
- 残余：默认无参 module() 仍 Direct（仅限 unbound 快照）；依赖默认 module() 且有绑定实体的旧调用方现在失败——fail-closed 收紧，需迁移说明。

### [M] HOT-1 热更能力描述与实际不符（鉴权面已修）
`zero-hot-update/pom.xml:16`
- 已修：默认拒绝 authorizer + 启动前装配 + AUTHORIZATION_DENIED，触发面鉴权问题闭环。
- 残余：pom 仍声称「CGLIB 和 ClassLoader 热更抽象」，实际仅 CSV 配置热更；能力缺口与描述矛盾未清。

### [M] PLAYER-2 会话绑定与传输连接无关联（协调器零装配）
`zero-player/.../PlayerConnectionCloseCoordinator.java`
- 已修：新增 PlayerConnectionCloseCoordinator 语义正确且有测试。
- 残余：主代码无任何装配点（死代码），断线自动解绑仍靠应用自行接线；未接线部署下绑定滞留路径依旧成立。

### [L] E-3 默认 dispatchExecutor 直接执行（装配未闭环）
`zero-runtime-event/.../EventRuntime.java:39`
- 已修：InMemoryEventBus 旧构造器已 @Deprecated，三参注入执行器且 resume() 送回 dispatchExecutor。
- 残余：EventRuntime 生产装配仍 `new InMemoryEventBus(sink)` 调已弃用的直执行构造器——生产路径核心影响未消除。

### [L] E-2 事件总线无重试能力（能力提供、未选用）
`zero-event/.../handler/RetryingEventHandler.java`
- 已修：新增 RetryingEventHandler 装饰器 + 测试。
- 残余：生产装配未使用它（能力提供、显式选用）；需要重试的业务仍须自行包装饰器。

### [L] FRAME-1 advance 异常后输入重放（语义明确化，顺序未动）
`zero-frame-sync/.../FrameMatchRuntime.java:179-201`
- 已修：catch 后锁存 `simulationFailure`，:126-127/:133-134 使失败后 match 隔离、tick/提交拒绝——行为从「静默重放」变为「显式隔离」（已复核有读取点，非死字段）。
- 残余：advance 顺序未动（:197 在 consumed 清理 :203 与 frameNo.set :214 之前）；隔离语义下该路径不再被重试触发，属可接受取舍。

### [L] MON-5 标签键长冲突（回避式修复，脆弱）
`zero-log/.../MonitorAlertLogSink.java:141`
- 已修方式：`key.length() > 52 ? key : "metric.label." + key`——长标签名不再加前缀（12+52=64 恰卡上限），避免 INVALID_RECORD。
- 残余：53+ 字符标签失去 `metric.label.` 命名空间；「长度大于所有固定键不会覆盖」依赖固定键均 ≤52 字符这一隐含不变量，无代码断言保护，未来新增更长固定键会静默被长标签覆盖。

---

## 3. 有意不修（设计取舍，复核确认维持）

| 条目 | 位置 | 取舍说明 |
|---|---|---|
| [L] R-4 STARTING 分支不可达 | DefaultGameRuntime | 防御冗余保留，无行为影响；新增 rejectOrIgnoreReentrantShutdown 处理回调内重入 |
| [L] NACOS-2 显式 nacos 缺配置静默降级 | ProductionNacosProviders.java:57-59 | 维持 MISSING_CONFIG 诊断态不阻断启动；运维误判风险仍在 |
| [L] K-RUN-2 reply topic 随机后缀累积 | ProductionKafkaRpcProvider.java | 实例隔离为有意设计（注释声明），新增前缀格式校验；旧 topic/group 不清理 |
| [L] KAFKA-6 permissions \u001f 编码有损（存疑） | KafkaRpcEnvelopeCodec.java | 维持存疑 Low；需核实签名范围后定级，本轮未动 |
| [L] KAFKA-7 残余：InMemory transport 无取消级联 | RpcClientFactory.java | cancel 级联依赖底层 future 取消触发 whenComplete 释放 permit；InMemory 路径默认长超时，可接受残余 |
| [L] ROOM-1 create 先发布再通知 | LocalRoomService.java | RoomCommandAdmission javadoc 声明不回滚（有意）；契约 hostile 但语义稳定 |
| [I] GM-4 仅 IP 白名单即构成授权 | GmOperationAuthorizer.java | 观察项；建议文档要求至少一类身份约束 |
| [I] PLAYER-4 loadPlayer/queryPlayer 无身份校验 | LocalPlayerService.java | 观察项；javadoc 定位内部服务 |

---

## 4. 修复新引入的问题（本轮复核确认）

### [M] NEW-1 HTTP 守卫 30s 期限硬编码，长请求被强制断连
`zero-net/.../netty/NettyHttpRequestGuard.java:23`（N2 修复引入）
- 问题：TIMEOUT_SECONDS=30 写死——完整请求 30s 绝对期限（InputDeadline.reset 只在收到完整 FullHttpRequest 时重置，慢业务 handler 不延期）+ 响应写 30s deadline；未纳入 ServerOptions/配置。
- 影响：业务处理超 30s 的合法请求（长轮询、大报表、SSE）被强制 fail 关连接；30s 不可调，最可能生产回归。
- 附带语义变化：同连接并发请求变严格串行（:50-59 busy 闸门），无配置开关；fail() 只 close，排队请求收裸 RST/FIN 而非错误响应，观测上区分不出「超预算拒绝」。

### [M] NEW-2 重复头语义从折叠变为 401 拒绝
`zero-net/.../netty/NettyHttpServer.java:308-311`（N1 修复引入）
- 问题：hasDuplicateHeaders 检出重复头后直接 401 关请求（原为后者覆盖折叠容忍）。
- 影响：前置代理注入重复头（如多个 X-Forwarded-For）时兼容性断裂；修复方向正确但需确认部署拓扑。

### [M] NEW-3 MigrationRecords 退役 ID 错误码语义错误 + 死分支
`zero-world/.../MigrationRecords.java:33-35,47`（WORLD-1 修复引入）
- 问题：get() 对已退役 ID 抛 `MIGRATION_CAPACITY_EXCEEDED`（"before replay watermark"）——错误码语义错误，且阻断 migration(id)/findMigration 查询已归档历史；acknowledgeThrough 把代码已不产出的 SOURCE_UNLOCKED 当终态（死分支）。
- 建议：换专用错误码（如 MIGRATION_RETIRED）或返回空；清理 SOURCE_UNLOCKED 死分支。

### [M] NEW-4 WORLD-1/RANK-2 水位用字典序比较
`zero-world/.../MigrationRecords.java:33,42,46,52`、zero-ranking 同模式（修复引入）
- 问题：`String.compareTo` 字典序水位——UUID 等非固定宽度幂等键会被误拒（新 ID 字典序小于水位）或绕过重放保护。
- 现状：javadoc 已声明「ID 须字典序与提交顺序一致」约束，风险转嫁调用方；框架未提供单调 ID 生成器配套。

### [L] NEW-5 DISK_TOTAL 误标 COUNTER
`zero-monitor/.../SystemMetricCollector.java:155`（MON-8 修复接线引入）
- 问题：磁盘总容量（可升可降瞬时值）注册为 MetricType.COUNTER；Prometheus TYPE 行导出 counter，rate() 查询语义误导。同文件 JVM_GC_COLLECTION_TOTAL 等才是正确 COUNTER 用例。应改回默认 GAUGE。

### [L] NEW-6 Actor 执行超时 30s 硬编码
`zero-runtime-actor/.../ActorRuntime.java:56`（A-1 修复引入）
- 问题：执行超时预算写死 30s，ActorSchedulerConfig 无配置口；慢 handler（长事务、大计算）可能被误隔离 lane。

### [L] NEW-7 SEC-1 密钥 32 字节下限为破坏性变更
`zero-security/.../SecurityMetadataAssertion.java:20`
- 问题：下限校验在启动期抛 IAE；存量短密钥配置直接启动失败。0.x 可接受；迁移说明 `docs/migrations/20261009-audit-round2.zh-CN.md` 已存在，需确认发布覆盖。

### [L] NEW-8 RetryingEventHandler 首次调用也走 supplyAsync
`zero-event/.../handler/RetryingEventHandler.java:41`（E-2 修复引入）
- 影响：同步 handler 多一次执行器跳转（轻微低效）；可首试同步、失败再异步重试。

### [L] NEW-9 LifecycleCallBoundary 超时仅 interrupt
`zero-runtime/.../assembly/LifecycleCallBoundary.java`（R-2 修复引入，已知边界）
- 现状：超时 interrupt 虚拟线程；不响应中断的用户 start 仍跑完（javadoc 已声明）。属机制固有限制，记录备查。

---

## 5. 已澄清排除的疑似项

- **K-RUN-5 traceId null NPE**：非 bug。`RpcTransportEvent.java:52` 记录构造器 `traceId = valueOrEmpty(traceId)` 已把 null 归一为空串，`KafkaProductionTelemetryObserver.java:139` 的 isBlank() 检查足够。
- **FRAME-1 simulationFailure 死字段**：非死字段。:126-127（提交路径）与 :133-134（tick 路径）均有读取，失败后 match 显式隔离。
- **DATA-8 Lua 字节级比较**：语义正确，但与 RedisCacheEnvelopeCodec 信封布局耦合（21/22 字节 nameSize 偏移），信封格式演进需同步脚本（脚本内已做长度防护）。
- **DISC-1 lookupCache 4096 硬上限**：满时仅一次过期淘汰，全部未过期时新查询不入缓存——语义安全，高基数查询下命中率可能骤降（观察项，无正确性问题）。

---

## 6. 覆盖说明

复核覆盖初版报告全部 11 节 133 条；判定基于当前工作树实读（行号均为现行号，初版行号因修复提交已有偏移）。验证：全仓主/测试源码离线编译通过；新增修复回归测试（ActorExecutionTimeoutTest、PlayerLoginRetryTest、PlayerConnectionCloseCoordinatorTest、GmSourceIpPolicyTest、UnicodeSensitiveFieldTest、NettyHttpRequestGuardTest、RankingRetentionTest、WorldRetentionTest 及事件重试、KCP 等）全部通过。未覆盖项与初版一致：各模块 src/test 全量、第三方依赖内部、scripts PS5.1 兼容细节、examples Demo 业务逻辑；第三方 kcp-owner.lua 的 Redis CAS 语义未独立核验。全量单测套件（全模块）本轮未整体重跑。

## 7. 2026-10-10 执行后复核

本节以 2026-10-10 当前工作树及本轮实际测试为准，覆盖本报告 §1、§2、§4 的 29 个条目。原条目保留作为发现记录；旧行号、严重度统计和状态不再代表执行后现状。

| 条目 | 执行后结论 |
|---|---|
| AOI-1 | 已修复：当前实体身份与比较快照分离，等值换引用后下一次校验可恢复快速路径；可变 state 的后续变化仍可观察。 |
| R-3、KCP-5、GM-2 | 当前报告结论不成立：JDK 21 probe 已验证 minimal stage 可转为 future；KCP 双重认证分别保护存储准入及异步完成时的身份新鲜度；GM 白名单项在构造期预解析，每请求只解析来源地址。 |
| B-2、GM-3、LOG-3、DATA-12 | 保留已记录的边界：维护停止与 executor close 使用各自有界预算，累计可能达到两段预算；GM 不自动租约回收 IN_PROGRESS，容量 4096 时 fail-closed；日志转义预算溢出拒绝整条记录；弃用 Redis 构造器仍以 commonPool 作为过渡默认，生产路径显式注入执行器。 |
| LOGIC-1、DATA-13、KCP-1 | 已闭环：逻辑目录按连接关闭/异常回收；磁盘 journal 读到不完整尾帧返回 READ_FAILED；KCP 静默期 FEC 恢复段获准处理，普通发送进度期限在冻结/静默期暂停。 |
| KAFKA-4 | 无限重建/重投已改为单响应最多 3 次发送失败后提交 offset 并记录 SEND_FAILED/错误日志；没有持久 DLQ，达到上限时响应会丢失，属于需部署监控的显式终止策略。 |
| DATA-4、DATA-14 | 已实施 fail-fast/显式执行域收紧；无 ID 实体须先由 codec.prepareForSave 分配并保留 ID；绑定快照须注入 PersistenceBindingExecutor。调用方影响见迁移说明。 |
| HOT-1、PLAYER-2、E-3、E-2、FRAME-1、MON-5 | 已闭环或按契约交付：热更描述限定为本地 CSV；Starter 提供精确 sessionId 连接关闭适配器供组合根接线；EventRuntime 使用受管 background executor；重试装饰器由业务显式选用；帧推进失败隔离；告警长标签使用 `metric.label.` 命名空间与有界摘要后缀。 |
| NEW-1、NEW-3、NEW-4、NEW-5、NEW-6、NEW-8 | 已修复：HTTP 时限由 ServerOptions 配置且默认 30 秒；退役迁移独立错误码；World/Ranking 使用显式单调 long 水位；磁盘总容量为 GAUGE；Actor 超时来自 ActorSchedulerConfig；事件重试首次调用同步启动。 |
| NEW-2、NEW-7、NEW-9 | NEW-2 维持重复头拒绝策略，安全边界不折叠重复字段；有代理重写重复头的部署需统一配置。NEW-7 的短密钥启动拒绝属于 0.x 安全收紧，迁移说明要求至少 32 字节。NEW-9 仍有 Java interrupt 不可强制终止不合作用户代码的机制边界，超时后资源隔离且不并发启动替代调用。 |

本轮还修复了 EventRuntime 运行时 provider 的依赖声明缺失，以及事件派发复用 logicExecutor 时可能自等待的问题：provider 现在声明 `zero.executors` 依赖，事件处理使用受管 background executor。World/Ranking 的旧字符串入口仍只为单进程过渡分配序号；跨进程重放必须使用调用方持久化的显式序号。

执行后验证：受影响模块集合 `-pl zero-data-redis,zero-logic,zero-server-starter,zero-rpc-kafka,zero-runtime-event,zero-world,zero-ranking,zero-net,zero-net-kcp,zero-aoi,zero-runtime -am test` 通过；Starter 全量测试及其依赖通过。质量和集成门禁以 `tasks/active/20261009-audit-round2/VERIFY.md` 中本轮证据为准。真实 Kafka/Redis/Nacos/数据库部署演练、Linux/macOS runner 和跨系统文件系统行为仍未由本机测试覆盖。
