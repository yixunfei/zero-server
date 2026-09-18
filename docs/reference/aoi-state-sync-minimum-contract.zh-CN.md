# AOI / 状态同步本地契约与扩展边界

当前状态：`implemented-local-minimum-slice / productionReady=false`。对应模块：`zero-aoi、zero-state-sync`，入口：`InMemoryAoiIndex、InMemoryStateSync`。使用方式见[可运行示例](../../examples/aoi-state-sync/README.zh-CN.md)，整体状态见[能力矩阵](../capability-matrix.zh-CN.md)。

本页先列当前本地限制和验证口径。末尾保留历史设计输入，其中候选类型、状态和跨服行为可能尚未实现，不应直接作为现行 API 使用。生产扩展仍需评审公共 API、Actor 所有权和数据边界；`requiresConfirmation=true`。

<!-- contract-id=aoi-state-sync-minimum-contract -->

## 本地容量与验证

### 定位

本文件记录 `examples/aoi-state-sync` 本地 fake in-memory API 的容量口径。它是可重复的行为边界，不是生产压测证据、吞吐承诺或 SLA。示例不修改 `zero-aoi` / `zero-state-sync`，不创建线程池，也不连接网络、数据库、缓存或消息系统。

### 固定默认值

| 限制 | 默认值 | 超限行为 |
| --- | ---: | --- |
| 单 scene 实体数 `maxEntities` | 32 | 拒绝新增实体 |
| 单次 visible query `maxVisible` | 8 | 截断为上限，结果仍按插入顺序稳定返回 |
| 单 observer 待处理事件 `maxObserverQueue` | 2 | 返回/记录 backpressure rejection，不静默丢弃后宣称成功 |
| 单 payload `maxPayloadBytes` | 256 bytes | 拒绝实体输入 |

测试可使用更小的限制验证边界。空白 ID、负视距、空值、重复实体和不存在的实体均拒绝。可见性使用 Chebyshev distance `<= viewRange`；当前实现为线性扫描 O(N)，不是 grid、quadtree 或 BVH。

### 工作负载与顺序

示例工作负载为单 JVM、单 scene owner：注册 observer，新增/移动实体，查询可见集，生成 snapshot，并尝试匹配或过期 baseline 的 delta。成功 mutation 递增 `sceneSeq`；每个 observer 的事件递增 `syncSeq`，事件保持出现/消失的因果顺序。snapshot 是当前可见集的完整基线，未知 baseline 返回 `resyncRequired=true`。

有界队列满时增加可观测的 `queueRejected` 计数；没有后台线程或阻塞等待，也没有静默删除权威实体状态。真实生产系统仍需定义优先级、合并 snapshot、传输重试和监控接入。

### 生产边界

该示例证明 API 路径和 focused tests 可在 JDK 21 本地运行，不证明生产容量、带宽、广播 fan-out、持久化、权限过滤、跨服迁移或客户端协议兼容性。marker 中始终保留 `productionReady=false`；任何生产容量结论都必须由目标 workload 的独立 benchmark、资源预算和故障测试给出。

<details>
<summary>历史设计输入与扩展候选（不代表现行 API）</summary>

以下内容保留最初的设计范围、备选方案和测试建议；其中“缺少”“下一步”等表述对应设计时点。当前已实现范围以上面的本地契约、示例和源码为准。

## 1. 定位

现有 `scripts/NewLocalGame.java --template scene-sync` 已能生成场景同步 / 简单 AOI 原型，帮助用户跑通：

- `.si` 协议。
- DTO / codec / BO / dispatcher 生成。
- 本地 Actor lane 串行修改场景实体状态。
- 进入场景、移动和可见性查询。
- 本地日志和指标输出。

正式框架能力还缺少：

- AOI 与状态同步的职责边界。
- 实体状态、可见集和兴趣订阅模型。
- snapshot / delta / full sync 的候选语义。
- 广播顺序、去重和背压边界。
- 场景 Actor、玩家 Actor 和跨服 Actor 的消息边界。
- 跨服迁移和状态交接的候选约束。
- 带宽、指标和性能证据口径。

本文的价值是把这些缺口整理成“下一步实现前必须确认什么”，而不是直接把 `scene-sync` 模板升级成生产承诺。

## 2. 范围

本草案覆盖：

- AOI、兴趣管理和状态同步定义。
- 候选模块边界。
- 实体状态模型。
- 可见性变更模型。
- snapshot / delta 候选。
- 广播顺序与背压候选。
- Actor / 线程归属候选。
- 跨服迁移边界候选。
- 日志、指标、TraceId 和 ErrorCode 候选。
- focused tests。

本草案不覆盖：

- 不创建 `zero-aoi`。
- 不创建 `zero-state-sync`。
- 不冻结正式 Java API。
- 不冻结客户端同步协议、协议 ID 或 codegen 规则。
- 不承诺 grid、quadtree、BVH、R-tree、navmesh 或其他空间索引实现。
- 不实现跨服场景迁移。
- 不实现压缩算法。
- 不承诺生产容量、同步频率、广播带宽或 SLA。
- 不更新 `docs/module-map.md` 声明候选模块已经存在。

## 3. 概念边界

| 概念 | 候选含义 | 不应混淆 |
| --- | --- | --- |
| AOI | 从世界 / 场景状态中计算某个观察者可见的实体候选集合 | AOI 不等于网络广播，也不等于权限系统 |
| Interest | 观察者对实体、区域、频道或状态类型的订阅关系 | Interest 不等于实体真实位置 |
| State Sync | 将服务端权威状态以 snapshot、delta 或事件形式同步给客户端或其他服务 | State Sync 不负责业务规则判定 |
| Snapshot | 某一时刻的状态快照 | Snapshot 不必等于持久化记录 |
| Delta | 相对上一版本或上一快照的状态差异 | Delta 不应脱离版本与基线单独发送 |
| Visibility Event | 可见集变化，例如 enter / leave / update | Visibility Event 不代表业务进入场景成功 |

首批建议先冻结抽象语义，不急于承诺具体空间索引。

## 4. 候选模块边界

候选模块只作为后续确认问题，不代表本仓库当前已经存在这些模块。

| 候选模块 | 候选职责 | 禁止越界 |
| --- | --- | --- |
| `zero-aoi` | AOI 索引抽象、可见集计算、兴趣订阅模型、可见性变更事件 | 不直接依赖 MongoDB、Redis、Kafka、Nacos；不直接创建线程池；不直接写网络 |
| `zero-state-sync` | snapshot / delta 模型、状态版本、同步事件、广播批次和去重边界 | 不冻结未确认的客户端协议 ID；不绕过 Actor 归属修改实体状态 |
| `zero-scene` 增强 | 场景实体权威状态、实体进入 / 移动 / 离开和状态查询 | 不把复杂 AOI 索引硬编码进基础场景 API |
| `zero-net` / `zero-rpc` 协作 | 承载客户端同步输出和跨服状态路由 | 不在未确认前改变 frame 格式、RPC 语义或服务发现策略 |

推荐依赖方向仍应保持：

```text
zero-aoi / zero-state-sync -> zero-scene / zero-game / zero-actor / zero-event / zero-protocol / zero-core
zero-aoi / zero-state-sync -> zero-log / zero-monitor
adapter -> zero-aoi / zero-state-sync only through SPI or service abstraction
starter -> zero-aoi / zero-state-sync
```

正式创建模块或改变依赖方向前，必须同步更新 `docs/module-map.md`。

## 5. 实体状态候选

实体状态建议拆成最小可扩展模型。

| 字段 | 候选含义 | 注意事项 |
| --- | --- | --- |
| `entityId` | 场景内实体唯一标识 | 不建议直接作为指标标签 |
| `entityType` | `PLAYER`、`NPC`、`OBJECT`、`PROJECTILE`、`TRIGGER` 等候选类型 | 类型集合需后续确认 |
| `sceneId` | 所属场景 | 跨服场景需额外 owner 信息 |
| `ownerActor` | 权威 Actor 地址或 lane key | 不应被客户端直接控制 |
| `position` | 位置候选，可为 2D / 3D / tile / grid | 坐标模型需和玩法类型绑定 |
| `rotation` | 朝向候选 | 可选 |
| `stateVersion` | 状态版本 | delta 必须依赖版本 |
| `visibilityMask` | 可见性过滤候选，例如阵营、隐身、相位 | 不等于权限系统 |
| `lastUpdateAt` | 最后更新时间 | 用于诊断和超时 |

候选规则：

- 场景内实体权威状态归属 scene actor / scene lane。
- 玩家全局状态仍归属 player actor。
- NPC 行为状态可能归属 NPC actor 或 scene actor，需按后续 NPC 契约确认。
- 客户端提交的是意图或输入，不是权威状态。
- AOI 计算不得直接修改实体状态。

## 6. AOI 索引候选

首批不冻结具体索引实现，只确认抽象能力。

候选索引能力：

- `addEntity`
- `updateEntity`
- `removeEntity`
- `queryVisible`
- `queryEnterLeave`
- `subscribeInterest`
- `unsubscribeInterest`

候选实现方向：

- 固定网格：简单、可预测，适合首批。
- 分层网格：适合大地图和不同视距。
- quadtree / octree：适合稀疏空间，但更新成本需验证。
- 自定义插件：适合项目特定空间划分。

候选原则：

- 索引更新由 scene actor 串行触发。
- 纯只读可见集计算可以基于不可变快照在辅助执行域计算，但结果回写必须通过 scene actor。
- AOI 查询不能阻塞 IO 线程。
- 大范围广播必须有背压和限流策略。

## 7. 可见性变更候选

可见性变化建议建模为三类：

| 事件 | 含义 |
| --- | --- |
| `EntityAppeared` | 实体对观察者新可见 |
| `EntityDisappeared` | 实体对观察者不可见 |
| `EntityUpdated` | 实体仍可见但状态变化 |

候选规则：

- 同一观察者同一场景内，可见性事件应带单调递增 `syncSeq`。
- `EntityAppeared` 应包含足够的初始状态。
- `EntityUpdated` 可以是 delta 或轻量事件。
- `EntityDisappeared` 应明确原因候选：离开 AOI、离开场景、隐藏、销毁、迁移。
- 可见性规则必须服务端权威计算。

## 8. Snapshot / Delta 候选

候选状态同步模式：

| 模式 | 适用场景 | 风险 |
| --- | --- | --- |
| Full Snapshot | 进入场景、重连、基线修复 | 数据量大 |
| Delta | 高频移动、属性变化、轻量状态同步 | 依赖版本和丢包恢复 |
| Event Sync | 离散事件，例如出现、消失、技能表现 | 事件遗漏会导致状态漂移 |
| Keyframe + Delta | 周期全量关键帧加增量 | 实现复杂，需要带宽预算 |

候选字段：

- `sceneId`
- `observerId`
- `syncSeq`
- `baselineVersion`
- `stateVersion`
- `entityId`
- `changeType`
- `payload`
- `traceId`
- `createdAt`

候选规则：

- delta 必须有明确基线版本。
- 客户端基线丢失时，应能请求 snapshot 或等待 keyframe。
- 状态同步消息应可重复去重。
- 重要状态和表现状态可以分级同步。
- 首批不冻结二进制协议格式。

## 9. Actor 与线程归属

候选归属：

- 场景实体权威状态绑定 scene actor / scene lane。
- AOI 索引更新由 scene actor 串行触发。
- 玩家连接和会话状态归属 player actor 或 connection/session 抽象。
- 网络发送不能在 scene actor 中阻塞。
- 跨 Actor 修改必须通过消息。
- 可能耗时的 snapshot 编码、压缩和远程发送应进入 remote IO / background 执行域。

候选流程：

```text
client input / timer / rpc
  -> command adapter
  -> validate immutable input
  -> dispatch message to scene actor
  -> mutate authoritative scene state
  -> update AOI index
  -> compute visibility changes
  -> emit sync event with traceId and syncSeq
  -> enqueue broadcast / encode / metrics side effect
```

需要避免：

- IO 线程直接计算大规模 AOI。
- scene actor 等待远程 RPC。
- AOI 计算直接跨 Actor 修改 player / NPC 状态。
- 为每个场景或实体直接创建线程池。
- 广播 fan-out 无背压。

## 10. 广播顺序与背压

候选顺序规则：

- 同一 `sceneId` 内状态变更维护单调递增 `sceneSeq`。
- 同一观察者同步消息维护单调递增 `syncSeq`。
- 同一观察者的出现、更新、消失事件必须保持因果顺序。
- 不承诺不同场景之间全局有序。
- 跨服场景只保证同一 scene owner 的逻辑顺序候选。

候选背压规则：

- 单观察者待发送队列应有上限。
- 大量实体进入可合并为 snapshot。
- 高频移动可合并或降频。
- 低优先级表现状态可丢弃，权威关键状态不可静默丢弃。
- 背压触发必须有日志和指标。

## 11. 跨服迁移边界

跨服 AOI / 状态同步属于高风险边界，本草案只保留候选约束：

- 同一实体同一时刻只能有一个权威 owner actor。
- 迁移需要 `migrationId`、source owner、target owner 和 owner version。
- 迁移期间需要冻结或缓冲输入策略。
- 迁移完成前，客户端可见性应保持一致或明确进入重同步。
- 远程迁移命令必须携带 `traceId`、`timeoutAt` 和幂等键。
- 超过 `timeoutAt` 的迁移命令应拒绝执行。

首批建议：

- local/prototype 仍保持单进程。
- 正式 `zero-aoi` 首轮可以只实现单 scene owner 语义。
- 跨服迁移先作为接口边界和 focused tests 草案，不直接实现。

## 12. 数据与缓存边界

候选原则：

- 实时 AOI 状态默认常驻内存。
- 持久化应通过统一 `Repository` / `DataService` 抽象。
- Snapshot 用于同步，不等同于落库格式。
- Redis 可用于短期跨进程状态、频道或迁移协调，但 `zero-aoi` 不得直接依赖 Redis adapter。
- 落库失败不应阻塞 scene actor 长时间等待。

需要确认：

- 场景实体状态是否需要定时落库。
- sync snapshot 是否需要持久化或只用于网络同步。
- 迁移失败时是否保留 source owner 状态。
- 客户端重连时从内存、缓存还是 DB 重新构建快照。

## 13. 日志、指标与 TraceId

日志候选分类：

- 业务日志：进入场景、离开场景、实体出现 / 消失。
- 性能日志：AOI 查询耗时、delta 计算耗时、广播耗时。
- 错误日志：非法状态、迁移失败、基线丢失。
- 安全日志：可见性越权、非法状态提交候选。

基础字段候选：

- `traceId`
- `sceneId`
- `entityId`
- `observerId`
- `sceneSeq`
- `syncSeq`
- `changeType`
- `entityType`
- `errorCode`
- `durationMs`

指标候选：

- `zero_aoi_entity_total`
- `zero_aoi_query_total`
- `zero_aoi_query_duration_seconds`
- `zero_aoi_visible_entity_count`
- `zero_state_sync_snapshot_total`
- `zero_state_sync_delta_total`
- `zero_state_sync_bytes_total`
- `zero_state_sync_dropped_total`
- `zero_scene_actor_queue_size`
- `zero_scene_migration_total`

指标标签原则：

- 标签必须低基数。
- 默认不把 `playerId`、`observerId`、`entityId`、`sceneId` 作为 Prometheus 标签。
- 可选标签候选：`sceneType`、`entityType`、`changeType`、`result`、`serverRole`。

## 14. ErrorCode 候选分类

候选分类只用于后续确认，不修改当前 ErrorCode 结构。

- `SCENE_NOT_FOUND`
- `SCENE_ENTITY_NOT_FOUND`
- `SCENE_ENTITY_ALREADY_EXISTS`
- `SCENE_STATE_VERSION_CONFLICT`
- `AOI_QUERY_FAILED`
- `AOI_INDEX_UPDATE_FAILED`
- `AOI_VISIBILITY_FORBIDDEN`
- `STATE_SYNC_BASELINE_MISSING`
- `STATE_SYNC_DELTA_REJECTED`
- `STATE_SYNC_BACKPRESSURE_REJECTED`
- `STATE_SYNC_PAYLOAD_TOO_LARGE`
- `SCENE_MIGRATION_DUPLICATE`
- `SCENE_MIGRATION_TIMEOUT`
- `SCENE_MIGRATION_FAILED`

对外错误必须绑定 ErrorCode；错误日志也必须绑定 ErrorCode。

## 15. Focused Tests

后续正式实现前，建议先确认以下测试口径：

| 测试名 | 验证点 |
| --- | --- |
| `entityAppearsWhenEnteringAoi` | 观察者进入范围后收到出现事件 |
| `entityDisappearsWhenLeavingAoi` | 实体离开范围后收到消失事件 |
| `entityUpdateKeepsSceneSequence` | 同一场景状态变更按 `sceneSeq` 排序 |
| `observerSyncSequenceIsMonotonic` | 同一观察者同步消息 `syncSeq` 单调递增 |
| `deltaRequiresKnownBaseline` | delta 缺基线时拒绝或触发 snapshot |
| `snapshotRestoresVisibilityState` | snapshot 能恢复可见实体集合 |
| `aoiComputationDoesNotMutateForeignActor` | AOI 计算不能直接跨 Actor 改状态 |
| `broadcastBackpressureDropsOnlyLowPriorityState` | 背压只允许丢弃低优先级表现状态 |
| `migrationUsesOwnerVersionFence` | 跨服迁移使用 owner version 防重复 owner |
| `metricsDoNotUseEntityIdLabels` | 指标标签不包含高基数实体标识 |

## 16. 高风险确认问题

进入实现前必须暂停并确认：

- 是否允许创建 `zero-aoi` 和 `zero-state-sync`？
- 是否允许新增公共 API / SPI？兼容策略是什么？
- 是否需要新增协议字段、协议 ID 或 codegen 规则？
- 首批 AOI 是否只冻结抽象，还是选择固定网格实现？
- snapshot / delta 是否进入第一批正式范围？
- 同步频率、带宽预算和背压策略如何定义？
- scene actor 与 AOI 计算是否允许分离？
- 跨服迁移是否进入第一批范围？
- 哪些 ErrorCode、日志字段和指标必须首批冻结？
- 是否需要同步更新 `docs/module-map.md`？

## 17. 推荐推进顺序

```text
scene-sync scaffold
  -> RunLocalScaffold
  -> 阅读生成项目的 BUSINESS_GUIDE.md / NEXT_STEPS.md
  -> docs/reference/aoi-state-sync-minimum-contract.zh-CN.md
  -> 提交 GitHub Design Proposal
  -> 维护者评审 API / 线程 / 广播 / 背压与兼容边界
  -> focused tests
  -> 最小 runtime 实现
  -> docs/module-map.md 同步
  -> quickstart / examples 同步
  -> performance evidence
```

## 18. 不证明什么

本文不证明：

- `zero-aoi` 已经存在。
- `zero-state-sync` 已经存在。
- AOI / state-sync API 已冻结。
- snapshot / delta 协议已冻结。
- 跨服迁移已可用。
- 生产容量或带宽达标。
- 用户已经确认高风险实现。

本文只证明：AOI / 状态同步正式化前，已经有一份可被 doctor、readiness、advisor、roadmap 和 implementation slice selector 发现的最小契约草案。

</details>


## 2026-09-17 报告核实修订

静止且状态未变化的可见实体不再产生 UPDATE；LEAVE 带观察者最后可见的实体，包含 entityId。坐标距离以 long 计算避免溢出。状态基线按 sceneId + observerId 隔离；重复序号或旧版本返回 IGNORED 且保持基线，不触发状态回退；正确新快照仍可修复 RESYNC_REQUIRED。
