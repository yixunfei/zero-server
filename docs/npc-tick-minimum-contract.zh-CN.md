# NPC tick 最小契约草案

状态：`implemented-local-minimum-slice`

确认要求：`requiresConfirmation=true`

关联切片：`npc-tick-minimum-contract`

本文是 `npc-tick` 从 local/prototype 模板走向正式组件前的最小契约输入。它只描述候选语义、风险边界和 focused tests，不创建 `zero-npc` / `zero-tick` 模块，不冻结公共 API，不改变 Actor 线程模型、协议、RPC、存储、缓存、日志字段或 ErrorCode 结构。

## 1. 定位

现有 `scripts/NewLocalGame.java --template npc-tick` 已能生成 AI NPC / tick 原型，帮助用户跑通：

- `.si` 协议。
- DTO / codec / BO / dispatcher 生成。
- 本地 Actor lane 串行修改 zone 状态。
- NPC 生成、行为切换、zone tick 推进和状态查询。
- 本地日志和指标输出。

正式框架能力还缺少：

- NPC 生命周期和归属模型。
- zone tick / NPC tick 调度语义。
- 行为状态、行为树 / 状态机 adapter 边界。
- tick 预算、跳帧、背压、降级和取消语义。
- 寻路、战斗 AI、感知查询等外部 adapter 的阻塞边界。
- 大量 NPC 场景下的性能证据和可观测性口径。

本文的价值是把这些缺口整理成“下一步实现前必须确认什么”，而不是直接把 `npc-tick` 模板升级成生产承诺。

## 2. 范围

本草案覆盖：

- NPC 生命周期候选。
- tick 调度和预算候选。
- 行为状态和 adapter 候选。
- Actor / 线程归属候选。
- 背压、降级和超时候选。
- 数据、缓存和热更边界候选。
- 日志、指标、TraceId 和 ErrorCode 候选。
- focused tests。

本草案不覆盖：

- 不创建 `zero-npc`。
- 不创建 `zero-tick`。
- 不冻结正式 Java API。
- 不冻结协议字段、协议 ID 或 codegen 规则。
- 不实现行为树引擎。
- 不实现寻路、战斗 AI 或感知系统。
- 不实现热更插件化行为 adapter。
- 不承诺生产 NPC 数量、tick rate、CPU 预算、延迟或 SLA。
- 不更新 `docs/module-map.md` 声明候选模块已经存在。

## 3. 概念边界

| 概念 | 候选含义 | 不应混淆 |
| --- | --- | --- |
| NPC | 由服务端驱动的非玩家实体 | 不等同于 scene entity 的全部能力 |
| Zone | 一组 NPC tick 和感知查询的逻辑区域 | 不等同于跨服 shard |
| Tick | 框架按节拍触发的状态推进机会 | 不等同于业务任意定时任务 |
| Behavior State | NPC 当前行为状态或行为树节点摘要 | 不等同于热更脚本实现 |
| Tick Budget | 单次 tick 可消耗的时间、数量或操作预算 | 不等同于生产容量承诺 |
| Backpressure | tick 或行为执行过载时的限流和降级策略 | 不等同于静默丢弃错误 |
| Adapter | 寻路、战斗、感知、脚本等外部能力接入点 | 不应在 Actor 线程内做不可控阻塞 |

首批建议先冻结抽象语义，不急于承诺完整行为树、寻路或热更插件。

## 4. 候选模块边界

候选模块只作为后续确认问题，不代表本仓库当前已经存在该模块。

| 候选模块 | 候选职责 | 禁止越界 |
| --- | --- | --- |
| `zero-npc` | NPC 生命周期、行为状态、NPC 命令和事件候选 | 不直接依赖 MongoDB、Redis、Kafka、Nacos；不直接写网络；不直接创建线程池 |
| `zero-tick` | tick 调度抽象、预算、取消、背压和指标候选 | 不绕过框架统一线程管理；不替代业务定时任务全部语义 |
| `zero-scene` 协作 | 场景实体、zone 归属和可见性查询协作 | 不把 NPC 行为和场景存储强耦合 |
| `zero-hot-update` 协作 | 后续行为 adapter 或脚本热更候选 | 不在首批承诺热更行为树 |
| `zero-monitor` / `zero-log` 协作 | 暴露 tick、队列、预算、超时、降级和错误指标 | 不引入高基数 NPC 标签 |

推荐依赖方向仍应保持：

```text
zero-npc -> zero-game / zero-actor / zero-event / zero-protocol / zero-core
zero-tick -> zero-core / zero-monitor / zero-log
zero-npc -> zero-tick only through explicit tick abstraction if zero-tick exists
adapter -> zero-npc only through SPI or service abstraction
starter -> zero-npc / zero-tick
```

正式创建模块或改变依赖方向前，必须同步更新 `docs/module-map.md`。

## 5. NPC 生命周期候选

候选状态：

| 状态 | 含义 |
| --- | --- |
| `CREATED` | 已创建但未进入 zone |
| `SPAWNED` | 已进入 zone，可被 tick 发现 |
| `ACTIVE` | 正常参与 tick 和行为决策 |
| `PAUSED` | 因过载、脚本暂停或业务策略临时停止行为 |
| `DESPAWNING` | 正在退出 zone，等待清理事件 |
| `REMOVED` | 已移除，不再参与 tick |

候选字段：

- `npcId`
- `templateId`
- `zoneId`
- `sceneId`
- `ownerActorId`
- `behaviorState`
- `spawnedAt`
- `lastTickAt`
- `version`
- `traceId`

候选规则：

- NPC 生命周期状态变更必须在归属 Actor 内串行执行。
- 同一 NPC 的状态版本应单调递增。
- 移除后的 NPC 不应继续参与 tick。
- spawn / despawn 需要发出可观测事件。
- 批量 spawn 必须有数量限制和错误处理。

## 6. Tick 调度候选

候选字段：

- `tickId`
- `zoneId`
- `tickNo`
- `tickRate`
- `scheduledAt`
- `startedAt`
- `finishedAt`
- `npcCount`
- `budgetMillis`
- `budgetOps`
- `traceId`

候选流程：

```text
tick scheduler
  -> dispatch ZoneTick to zone actor
  -> collect active NPC ids
  -> apply budget policy
  -> run behavior step for selected NPCs
  -> emit state changes and domain events
  -> record metrics / logs
  -> schedule next tick or apply backpressure
```

候选规则：

- tick 调度必须由框架统一管理，不允许业务直接创建线程池。
- zone actor 不应等待不可控远程 IO。
- 单次 tick 必须有预算上限。
- 超预算时必须显式记录降级、跳帧或延后。
- tick 任务必须可取消、可观测、可限流。
- 不同 zone 的 tick 是否并行，应由 Actor 归属和调度策略决定。

## 7. 行为状态与 Adapter 候选

候选行为类型：

- idle。
- patrol。
- chase。
- attack。
- flee。
- interact。
- scripted。
- disabled。

候选 adapter：

| Adapter | 候选职责 | 风险边界 |
| --- | --- | --- |
| `BehaviorAdapter` | 根据 NPC 状态和上下文计算下一步行为 | 不在 Actor 线程内阻塞 |
| `PathfindingAdapter` | 路径查询和导航 | 可能 CPU 重，需异步或预算 |
| `CombatAiAdapter` | 战斗目标选择和技能决策 | 需要超时和异常隔离 |
| `PerceptionAdapter` | 感知范围和目标筛选 | 需要和 AOI / scene 边界协作 |
| `ScriptAdapter` | 活动或脚本行为接入 | 需要热更、权限和隔离确认 |

候选规则：

- 行为 adapter 的输入应尽量不可变。
- adapter 失败不能导致 Actor lane 静默中断。
- 业务异常默认不重试，是否重试由业务显式决定。
- 外部 adapter 超时应产生 ErrorCode、错误日志和指标。
- 行为状态变更应记录 `npcId`、`oldState`、`newState`、`reason` 和 `traceId`。

## 8. 背压、降级与跳帧候选

候选策略：

- 按 zone 限制每 tick 最大 NPC 数。
- 按行为类型设置预算。
- 低优先级 NPC 降频。
- 超预算 NPC 延后到下一 tick。
- 暂停非战斗 NPC 行为。
- 降级为 idle / patrol。
- 熔断异常 adapter。

必须避免：

- 无界 tick 队列。
- 无界 NPC 批量 spawn。
- 每个 NPC 创建独立定时器或线程。
- 在 Actor 线程内执行阻塞寻路或远程 RPC。
- 因异常而停止整个 zone 后续 tick。

首批建议只冻结策略接口和 focused tests，不固定唯一默认策略。

## 9. Actor 与线程归属

候选归属：

- 玩家在线状态仍归属 player actor。
- 场景状态仍归属 scene actor。
- 同一 zone 的 NPC 状态建议绑定 zone actor / scene actor / npc lane。
- tick 调度线程只投递 tick 消息，不直接修改 NPC 状态。
- 行为 adapter 若可能阻塞，应走可控异步执行域并回投 Actor。

候选流程：

```text
GM/dev command / timer / scene event
  -> validate command
  -> dispatch message to zone actor
  -> mutate NPC lifecycle or behavior state
  -> tick message advances selected NPCs
  -> emit NPC event with traceId
  -> enqueue persistence / metrics / log side effects
```

需要避免：

- IO 线程直接运行行为决策。
- zone actor 等待远程 RPC。
- tick scheduler 直接修改 NPC Map。
- 跨 Actor 直接引用并修改 NPC 或 scene state。
- 快速连续 tick 造成队列堆积但没有指标。

## 10. 数据、缓存与热更边界

候选原则：

- NPC 实时状态默认常驻内存。
- NPC 模板配置可热更，但状态结构变更不能依赖普通代理安全完成。
- 持久化应通过统一 `Repository` / `DataService` 抽象。
- 高频 tick 中不应同步落库。
- 关键 NPC 或世界 boss 是否持久化需业务确认。
- 行为脚本或活动 adapter 若支持热更，必须记录操作人、时间、权限、IP、目标和回滚方式。

需要确认：

- 普通 NPC 是否需要持久化。
- 世界 boss / 任务 NPC 是否需要恢复。
- 行为配置热更是否进入第一批范围。
- 落库失败是否影响 NPC 行为继续。

## 11. 日志、指标与 TraceId

日志候选分类：

- 业务日志：spawn、despawn、行为切换、关键战斗行为。
- 性能日志：tick 耗时、adapter 耗时、队列长度、降级次数。
- 错误日志：行为失败、tick 失败、配置缺失、adapter 超时。
- 安全日志：GM / dev 指令创建 NPC、热更行为脚本、异常行为注入。

基础字段候选：

- `traceId`
- `zoneId`
- `sceneId`
- `npcId`
- `templateId`
- `tickNo`
- `behaviorState`
- `tickRate`
- `budgetMillis`
- `durationMs`
- `errorCode`

指标候选：

- `zero_npc_active_total`
- `zero_npc_spawn_total`
- `zero_npc_despawn_total`
- `zero_npc_tick_total`
- `zero_npc_tick_duration_seconds`
- `zero_npc_tick_skipped_total`
- `zero_npc_behavior_transition_total`
- `zero_npc_behavior_error_total`
- `zero_npc_adapter_timeout_total`
- `zero_npc_actor_queue_size`

指标标签原则：

- 标签必须低基数。
- 默认不把 `npcId`、`uid`、`sceneId` 作为 Prometheus 标签。
- 可选标签候选：`zoneType`、`behaviorType`、`result`、`degradeReason`、`serverRole`。

## 12. ErrorCode 候选分类

候选分类只用于后续确认，不修改当前 ErrorCode 结构。

- `NPC_NOT_FOUND`
- `NPC_STATE_INVALID`
- `NPC_TEMPLATE_NOT_FOUND`
- `NPC_SPAWN_LIMIT_EXCEEDED`
- `NPC_TICK_OVERLOAD`
- `NPC_TICK_CANCELLED`
- `NPC_BEHAVIOR_REJECTED`
- `NPC_BEHAVIOR_FAILED`
- `NPC_ADAPTER_TIMEOUT`
- `NPC_PATHFINDING_UNAVAILABLE`
- `NPC_HOT_UPDATE_REJECTED`
- `NPC_PERMISSION_DENIED`

对外错误必须绑定 ErrorCode；错误日志也必须绑定 ErrorCode。

## 13. Focused Tests

后续正式实现前，建议先确认以下测试口径：

| 测试名 | 验证点 |
| --- | --- |
| `npcLifecycleTransitionsAreValid` | NPC 生命周期状态流转合法 |
| `removedNpcIsNotTickedAgain` | 已移除 NPC 不再参与 tick |
| `zoneTickRunsInsideOwnerActor` | zone tick 在归属 Actor 串行执行 |
| `tickBudgetLimitsProcessedNpcCount` | tick 预算限制处理数量 |
| `overBudgetTickRecordsDegradation` | 超预算 tick 记录降级或跳帧 |
| `behaviorAdapterTimeoutIsIsolated` | 行为 adapter 超时不阻断整个 zone |
| `behaviorTransitionIncrementsVersion` | 行为状态变更递增版本 |
| `tickSchedulerDoesNotCreatePerNpcThreads` | 不为每个 NPC 创建独立线程 |
| `metricsDoNotUseNpcIdLabels` | 指标标签不包含高基数 npcId |
| `gmSpawnCommandProducesAuditEvent` | GM / dev 创建 NPC 产生审计或安全事件 |

## 14. 高风险确认问题

进入实现前必须暂停并确认：

- 是否允许创建 `zero-npc` 和 `zero-tick`？
- 是否允许新增公共 API / SPI？兼容策略是什么？
- 是否需要新增协议字段、协议 ID 或 codegen 规则？
- 首批是否实现 tick scheduler，tick rate 默认值是什么？
- NPC 生命周期和 zone 归属如何定义？
- tick 预算、跳帧、背压和降级策略如何定义？
- 行为树、状态机、寻路和战斗 AI 哪些进入第一批范围？
- 行为 adapter 是否允许异步执行和超时回投？
- 哪些 ErrorCode、日志字段和指标必须首批冻结？
- 是否需要同步更新 `docs/module-map.md`？

## 15. 推荐推进顺序

```text
npc-tick scaffold
  -> RunLocalScaffold
  -> 阅读生成项目的 BUSINESS_GUIDE.md / NEXT_STEPS.md
  -> docs/npc-tick-minimum-contract.zh-CN.md
  -> 提交 GitHub Design Proposal
  -> 维护者评审 tick / 线程 / 背压 / 降级与兼容边界
  -> focused tests
  -> 最小 runtime 实现
  -> docs/module-map.md 同步
  -> quickstart / examples 同步
  -> performance evidence
```

## 16. 不证明什么

本文不证明：

- `zero-npc` 或 `zero-tick` 已经存在。
- NPC / tick API 已冻结。
- 行为树、寻路或战斗 AI 已实现。
- tick scheduler、背压或降级已经实现。
- 热更行为 adapter 已完成。
- 生产 NPC 数量、CPU 预算、延迟或 SLA 达标。
- 用户已经确认高风险实现。

本文只证明：NPC tick 正式化前，已经有一份可被 doctor、readiness、advisor、roadmap 和 implementation slice selector 发现的最小契约草案。
