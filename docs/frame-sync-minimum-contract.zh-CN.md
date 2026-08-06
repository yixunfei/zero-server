# 帧同步最小契约草案

状态：`draft`

确认要求：`requiresConfirmation=true`

关联切片：`frame-sync-minimum-contract`

本文是 `frame-sync` 从 local/prototype 模板走向正式组件前的最小契约输入。它只描述候选语义、风险边界和 focused tests，不创建 `zero-frame-sync` 模块，不冻结公共 API，不改变 Actor 线程模型、协议、RPC、存储、缓存、日志字段或 ErrorCode 结构。

## 1. 定位

现有 `scripts/NewLocalGame.java --template frame-sync` 已能生成帧同步 / lockstep 原型，帮助用户跑通：

- `.si` 协议。
- DTO / codec / BO / dispatcher 生成。
- 本地 Actor lane 串行修改比赛状态。
- 加入比赛、提交帧输入、推进帧和查询快照摘要。
- 本地日志和指标输出。

正式框架能力还缺少：

- 固定帧时钟和 tick 调度语义。
- 输入收集、输入序号、迟到输入和重复输入处理。
- 帧推进、快照、补帧和回滚边界。
- 可靠广播、观战和断线恢复候选语义。
- 反作弊、输入校验和客户端可信边界。
- 延迟、带宽、背压和性能证据口径。

本文的价值是把这些缺口整理成“下一步实现前必须确认什么”，而不是直接把 `frame-sync` 模板升级成生产承诺。

## 2. 范围

本草案覆盖：

- 固定帧时钟候选。
- 输入模型和排序候选。
- 帧状态、快照和回滚边界候选。
- 广播可靠性和背压候选。
- 断线、补帧和观战候选。
- Actor / 线程归属候选。
- 日志、指标、TraceId 和 ErrorCode 候选。
- focused tests。

本草案不覆盖：

- 不创建 `zero-frame-sync`。
- 不冻结正式 Java API。
- 不冻结客户端帧协议、协议 ID 或 codegen 规则。
- 不实现确定性模拟引擎。
- 不实现回滚系统。
- 不实现传输层可靠 UDP、KCP 或 WebSocket。
- 不实现反作弊系统。
- 不承诺生产 tick rate、延迟、带宽、容量或 SLA。
- 不更新 `docs/module-map.md` 声明候选模块已经存在。

## 3. 概念边界

| 概念 | 候选含义 | 不应混淆 |
| --- | --- | --- |
| Match / Lockstep Room | 承载同一局固定帧推进的逻辑容器 | 不等同于通用 room 生命周期，需和 room 契约协作 |
| Frame Clock | 服务端或权威节点推进帧号的节拍 | 不等同于客户端本地渲染帧率 |
| Frame Input | 某玩家针对某一帧或目标帧提交的输入 | 不等同于权威状态 |
| Input Batch | 某一帧收集到的一组玩家输入 | 不保证所有玩家都有输入 |
| Snapshot | 某一帧的状态摘要或可回滚状态 | 不等同于持久化记录 |
| Rollback | 回到旧帧并重放输入 | 首批可只保留边界，不实现 |
| Spectator Stream | 观战同步流 | 需要延迟、脱敏和带宽策略 |

首批建议先冻结抽象语义，不急于承诺完整 rollback 或反作弊。

## 4. 候选模块边界

候选模块只作为后续确认问题，不代表本仓库当前已经存在该模块。

| 候选模块 | 候选职责 | 禁止越界 |
| --- | --- | --- |
| `zero-frame-sync` | 固定帧时钟、输入收集、帧推进、快照摘要、补帧边界、观战流候选 | 不直接依赖 MongoDB、Redis、Kafka、Nacos；不直接创建线程池；不直接写网络 |
| `zero-room` 协作 | 小局生命周期、成员状态、开始 / 结算边界 | 不把通用 room API 和帧同步细节强耦合 |
| `zero-net` / `zero-rpc` 协作 | 承载输入提交、广播和跨服路由 | 不在未确认前改变 frame 格式、RPC 语义或服务发现策略 |
| `zero-monitor` / `zero-log` 协作 | 暴露 tick、队列、延迟、丢弃和错误指标 | 不引入高基数标签 |

推荐依赖方向仍应保持：

```text
zero-frame-sync -> zero-game / zero-actor / zero-event / zero-protocol / zero-core
zero-frame-sync -> zero-log / zero-monitor
zero-frame-sync -> zero-room only through explicit service abstraction if zero-room exists
adapter -> zero-frame-sync only through SPI or service abstraction
starter -> zero-frame-sync
```

正式创建模块或改变依赖方向前，必须同步更新 `docs/module-map.md`。

## 5. 固定帧时钟候选

候选字段：

- `matchId`
- `tickRate`
- `frameNo`
- `frameDurationMillis`
- `startedAt`
- `serverNow`
- `maxInputDelayFrames`
- `maxRollbackFrames`
- `traceId`

候选规则：

- 服务端必须有权威 `frameNo`。
- 客户端渲染帧率不得驱动服务端 `frameNo`。
- tick 调度必须可观测、可取消、可限流。
- tick 抖动需要记录性能日志或指标。
- 首批可只确认固定 tick rate，不实现动态 tick rate。

## 6. 输入模型候选

候选字段：

- `matchId`
- `uid`
- `inputSeq`
- `targetFrame`
- `clientFrame`
- `inputPayload`
- `clientSentAt`
- `serverReceivedAt`
- `traceId`
- `idempotencyKey`

候选规则：

- 同一玩家 `inputSeq` 应单调递增。
- 同一玩家重复 `inputSeq` 必须幂等处理。
- 早到输入可缓冲到 `targetFrame`。
- 迟到输入可拒绝、补帧、回滚或降级，必须由策略明确。
- 输入 payload 尺寸必须有限制。
- 客户端提交输入不等同于权威状态。

## 7. 帧推进候选

候选流程：

```text
frame clock tick
  -> dispatch TickFrame to match actor
  -> collect buffered inputs for frameNo
  -> apply missing-input policy
  -> build input batch
  -> advance simulation hook
  -> emit frame committed event
  -> enqueue broadcast / snapshot / metrics side effect
```

候选 missing-input 策略：

- 等待到最大延迟窗口。
- 使用空输入。
- 使用上一帧输入。
- 标记玩家掉线 / 卡顿。
- 暂停比赛。

首批建议只冻结策略接口和 focused tests，不固定唯一默认策略。

## 8. Snapshot / Rollback 候选

候选 snapshot 类型：

| 类型 | 用途 |
| --- | --- |
| `InputSnapshot` | 记录某帧输入批次 |
| `StateDigest` | 记录状态摘要，用于一致性校验 |
| `RollbackSnapshot` | 可恢复状态，用于回滚 |
| `SpectatorSnapshot` | 观战或重连基线 |

候选规则：

- `StateDigest` 可以先作为轻量校验，不等同完整状态。
- 完整 rollback snapshot 成本高，首批可只保留接口边界。
- 回滚窗口必须有限。
- 回滚重放必须绑定输入日志和 frameNo。
- snapshot 生成不能长时间阻塞 match actor。

## 9. 广播、补帧与观战

候选广播内容：

- `FrameInputBatch`
- `FrameCommitted`
- `StateDigest`
- `KeyframeSnapshot`
- `ResyncRequired`

候选规则：

- 同一 match 内广播按 `frameNo` 有序。
- 关键帧和补帧必须可去重。
- 慢客户端不能无限拖累 match actor。
- 观战流可以延迟若干帧，降低信息泄漏风险。
- 补帧窗口必须有限，超过窗口应触发重同步或断开策略。

不在首批冻结：

- 可靠 UDP。
- KCP。
- 客户端预测。
- 完整 rollback 算法。
- 观战回放存储格式。

## 10. Actor 与线程归属

候选归属：

- 同一 match 的帧状态绑定 match actor / room actor / frame lane。
- 玩家全局状态仍归属 player actor。
- 网络输入解码不能在 match actor 中阻塞。
- 广播发送和快照压缩不能长时间阻塞 match actor。
- 跨 Actor 修改必须通过消息。
- tick 调度由框架统一管理，不允许业务直接创建线程池。

候选流程：

```text
client input / timer
  -> command adapter
  -> validate immutable input
  -> dispatch message to match actor
  -> mutate frame buffer in match actor
  -> tick advances frame in match actor
  -> emit frame event with traceId and frameNo
  -> enqueue broadcast / snapshot / metrics side effect
```

需要避免：

- IO 线程直接运行模拟或回滚。
- match actor 等待远程 RPC。
- 每个 match 创建独立线程池。
- 广播 fan-out 无背压。
- 客户端输入直接修改权威状态。

## 11. 反作弊边界

首批只建议确认输入可信边界：

- 客户端 `clientFrame` 和 `clientSentAt` 不可完全信任。
- 服务端应记录 `serverReceivedAt`。
- 超前输入、重复输入、过大 payload、非法状态输入应绑定 ErrorCode。
- 需要保留输入日志用于争议排查。
- 观战流应考虑延迟和可见信息裁剪。

不在首批实现：

- 完整反作弊评分。
- 确定性校验服务。
- 客户端二进制校验。
- 行为模型识别。

## 12. 数据与缓存边界

候选原则：

- 实时帧状态默认常驻内存。
- 输入日志和关键帧是否持久化需业务确认。
- 持久化应通过统一 `Repository` / `DataService` 抽象。
- Redis 可用于短期跨进程状态或观战缓冲，但 `zero-frame-sync` 不得直接依赖 Redis adapter。
- 结算结果应与 room / settlement 幂等策略协作。

需要确认：

- 是否需要保存整局输入日志。
- 是否需要保存观战 / 回放数据。
- match 崩溃后是否允许恢复，还是直接判定异常结束。
- 落库失败是否影响比赛进行。

## 13. 日志、指标与 TraceId

日志候选分类：

- 业务日志：加入比赛、开始、结束。
- 性能日志：tick 耗时、输入等待、广播耗时、快照耗时。
- 错误日志：非法输入、帧推进失败、补帧失败。
- 安全日志：超前输入、重复输入、疑似作弊输入。

基础字段候选：

- `traceId`
- `matchId`
- `roomId`
- `frameNo`
- `uid`
- `inputSeq`
- `targetFrame`
- `tickRate`
- `errorCode`
- `durationMs`

指标候选：

- `zero_frame_match_active_total`
- `zero_frame_tick_total`
- `zero_frame_tick_duration_seconds`
- `zero_frame_input_total`
- `zero_frame_input_late_total`
- `zero_frame_input_duplicate_total`
- `zero_frame_broadcast_total`
- `zero_frame_snapshot_total`
- `zero_frame_rollback_total`
- `zero_frame_actor_queue_size`

指标标签原则：

- 标签必须低基数。
- 默认不把 `uid`、`matchId`、`roomId` 作为 Prometheus 标签。
- 可选标签候选：`mode`、`tickRateBucket`、`result`、`inputStatus`、`serverRole`。

## 14. ErrorCode 候选分类

候选分类只用于后续确认，不修改当前 ErrorCode 结构。

- `FRAME_MATCH_NOT_FOUND`
- `FRAME_MATCH_STATE_INVALID`
- `FRAME_INPUT_DUPLICATE`
- `FRAME_INPUT_TOO_EARLY`
- `FRAME_INPUT_TOO_LATE`
- `FRAME_INPUT_PAYLOAD_TOO_LARGE`
- `FRAME_INPUT_REJECTED`
- `FRAME_TICK_OVERLOAD`
- `FRAME_SNAPSHOT_FAILED`
- `FRAME_ROLLBACK_UNSUPPORTED`
- `FRAME_RESYNC_REQUIRED`
- `FRAME_SPECTATOR_REJECTED`
- `FRAME_ANTICHEAT_REJECTED`

对外错误必须绑定 ErrorCode；错误日志也必须绑定 ErrorCode。

## 15. Focused Tests

后续正式实现前，建议先确认以下测试口径：

| 测试名 | 验证点 |
| --- | --- |
| `frameClockAdvancesMonotonically` | 服务端 frameNo 单调递增 |
| `inputSeqIsIdempotentPerPlayer` | 同一玩家重复 inputSeq 不产生重复输入 |
| `earlyInputBufferedUntilTargetFrame` | 早到输入缓冲到目标帧 |
| `lateInputRejectedOrMarkedByPolicy` | 迟到输入按策略拒绝或标记 |
| `frameInputBatchOrderIsStable` | 同一帧输入批次排序稳定 |
| `missingInputPolicyIsExplicit` | 缺输入策略必须显式配置或声明默认 |
| `broadcastOrderFollowsFrameNo` | 广播顺序跟随 frameNo |
| `snapshotDigestMatchesCommittedFrame` | 快照摘要和已提交帧一致 |
| `matchActorDoesNotBlockOnBroadcast` | match actor 不阻塞等待网络广播 |
| `metricsDoNotUseMatchIdLabels` | 指标标签不包含高基数 matchId |

## 16. 高风险确认问题

进入实现前必须暂停并确认：

- 是否允许创建 `zero-frame-sync`？
- 是否允许新增公共 API / SPI？兼容策略是什么？
- 是否需要新增协议字段、协议 ID 或 codegen 规则？
- 首批是否实现固定帧时钟，tick rate 默认值是什么？
- 输入延迟窗口、补帧窗口和回滚窗口如何定义？
- missing-input 默认策略是什么？
- 广播可靠性和背压策略如何定义？
- 观战和反作弊是否进入第一批范围？
- 哪些 ErrorCode、日志字段和指标必须首批冻结？
- 是否需要同步更新 `docs/module-map.md`？

## 17. 推荐推进顺序

```text
frame-sync scaffold
  -> RunLocalScaffold
  -> 阅读生成项目的 BUSINESS_GUIDE.md / NEXT_STEPS.md
  -> docs/frame-sync-minimum-contract.zh-CN.md
  -> 提交 GitHub Design Proposal
  -> 维护者评审时钟 / 输入 / 回滚 / 广播与兼容边界
  -> focused tests
  -> 最小 runtime 实现
  -> docs/module-map.md 同步
  -> quickstart / examples 同步
  -> performance evidence
```

## 18. 不证明什么

本文不证明：

- `zero-frame-sync` 已经存在。
- 帧同步 API 已冻结。
- 客户端协议已冻结。
- 固定帧时钟、补帧或回滚已经实现。
- 观战或反作弊已经完成。
- 生产容量、延迟或带宽达标。
- 用户已经确认高风险实现。

本文只证明：帧同步正式化前，已经有一份可被 doctor、readiness、advisor、roadmap 和 implementation slice selector 发现的最小契约草案。
