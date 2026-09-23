# scene-move-loop 性能证据口径草案

```text
zero-performance-evidence=track|id=scene-move-loop|status=readiness|benchmarkComplete=false|productionReady=false
```

本文定义 `scene-move-loop` 首批性能证据的可复现口径草案。它服务于 zeroServer “高性能多场景处理”、RPG 场景同步和快速脚手架目标，但当前只作为后续 benchmark、场景负载与长稳任务的输入，不是场景容量报告，也不是正式 AOI、广播带宽或生产 SLA 承诺。

## 1. 定位

`scene-move-loop` 首先覆盖 `LocalSceneService` 的 enter、moveWithResult、queryEntity、listEntities 和 leaveScene，以及 `GameActorGateway -> ActorScheduler -> scene lane handler` 的完成链路。它还记录 RPG generated BO 和 scene-sync 脚手架的协议/dispatcher/可见性/日志/指标包装，但这些路径必须与基础 SceneService 分表。

该 track 必须分五层报告：request/record only、LocalSceneService + LocalActorScheduler、LocalSceneService + ExecutorActorScheduler、generated dispatcher + scene-sync prototype、future AOI/state-sync/broadcast reservation。后两层不得反向解释为基础移动 handler 成本。

当前实现有几项必须原样记录的行为：

- `moveWithResult` 对已有实体返回 previous/current；对缺失实体会创建状态且 previous 为空。
- `listEntities` 使用 `List.copyOf(values())`，返回不可变、无序快照，复制成本随实体数增长。
- `LocalActorScheduler` 在调用线程确定性推进；`ExecutorActorScheduler` 使用框架受管 executor，两者线程切换和完成成本不同。
- scene-sync 模板使用自己的 `SceneStore`、线性可见性扫描和 handler 内日志/指标，不是 `LocalSceneService`，也不是正式 AOI。
- `zero-player` 只参与玩家到场景的业务交接，`zero-cache` 不在当前基础 move handler 内；不得把两者成本混入基础移动结果。

当前任务只固定“测什么、怎么记录、哪些边界不能误碰”。它不创建 SceneService 或 ActorScheduler，不投递消息，不运行模板/示例，不修改场景实现，不新增 benchmark profile，不定义阈值。

## 2. 源码证据

| 证据 | 当前状态 | 用途 |
| --- | --- | --- |
| [zero-scene/pom.xml](../../../zero-scene/pom.xml) | 已有 | 场景基础服务模块入口 |
| [zero-actor/pom.xml](../../../zero-actor/pom.xml) / [zero-game/pom.xml](../../../zero-game/pom.xml) | 已有 | scene lane 调度与业务 gateway 入口 |
| [zero-player/pom.xml](../../../zero-player/pom.xml) / [zero-cache/pom.xml](../../../zero-cache/pom.xml) | 已有 | 玩家交接与未来缓存协作边界；不属于基础 move handler |
| `SceneService` | 已有 | enter/move/query/list/leave 公共原型契约 |
| `LocalSceneService` | 已有 | scene lane 命令、状态 Map、移动和快照实现入口 |
| `SceneEnterRequest` / `SceneMoveRequest` / `SceneLeaveRequest` | 已有 | 不可变请求与 traceId 输入 |
| `SceneEntityState` / `ScenePosition` | 已有 | 不可变实体坐标状态 |
| `SceneMoveResult` / `SceneLeaveResult` | 已有 | previous/current 与离开状态结果 |
| `LaneKey` / `ActorScheduler` | 已有 | scene lane 身份和投递公共边界 |
| `LocalActorScheduler` | 已有 | 调用线程确定性 lane 推进参考实现 |
| `ExecutorActorScheduler` | 已有 | executor-backed lane 队列和异步推进参考实现 |
| `GameActorGateway` / `GameRequestContext` | 已有 | traceId、lane 和业务命令到 ActorMessage 的包装入口 |
| `LocalPlayerService` | 已有 | 玩家在线状态到场景调用的业务交接参考；不计入基础 move |
| `Stage3FoundationRuntimeTest` | 已有 | starter 受管 executor 下 enter/move/query/list/leave 功能证据 |
| `Stage3GeneratedBoFullLoopTest` | 已有 | DSL -> generated dispatcher -> scene lane -> log/metric/GM 功能证据 |
| `RpgMinimalApplication` / `RpgMinimalApplicationTest` | 已有 | 开箱即用 RPG 场景循环示例与回归入口 |
| `templates/scene-sync-scaffold/Application.java.tpl` | 已有 | generated scene-sync、线性可见性和观测包装原型 |
| `templates/scene-sync-scaffold/ApplicationTest.java.tpl` | 已有 | scene-sync 本地脚手架回归入口 |
| [docs/threading-model.zh-CN.md](../../../docs/threading-model.zh-CN.md) | 已有 | scene actor、player actor 和跨 Actor 消息边界 |
| [docs/reference/aoi-state-sync-minimum-contract.zh-CN.md](../../../docs/reference/aoi-state-sync-minimum-contract.zh-CN.md) | 已有 | 正式 AOI/状态同步候选语义和高风险确认边界 |
| [docs/optimization-roadmap.zh-CN.md](../../../docs/optimization-roadmap.zh-CN.md) | 已有 | S4E 场景移动性能证据要求 |
| [docs/operations/api-compatibility-gate.zh-CN.md](../../../docs/operations/api-compatibility-gate.zh-CN.md) | 已有 | Scene/Actor limited API 契约审阅 |
| [docs/operations/performance.zh-CN.md](../../../docs/operations/performance.zh-CN.md) | 已有 | 性能 track 聚合入口 |

这些证据说明场景原型、Actor 边界和功能链路已经存在，不说明移动吞吐、场景容量、快照成本、AOI 复杂度或广播预算达标。`zero-scene` 当前没有独立模块级测试类，功能证据主要位于 starter 与示例测试；正式 benchmark 不能把这一缺口隐藏掉。

## 3. workload 矩阵

| workloadId | 场景 | 隔离目标 | 当前阶段 |
| --- | --- | --- | --- |
| `scene-enter-single-entity` | 空场景中 enter 一个实体 | 观察请求、gateway、lane、状态构造和 Map 写入成本 | 计划 |
| `scene-move-existing-entity` | 已 enter 实体连续移动 | 观察 previous/current、坐标替换和 scene Map 更新成本 | 计划 |
| `scene-move-missing-entity` | 未 enter 实体直接 move | 记录当前 create-on-move 行为和 previous empty 成本，不改变语义 | 计划 |
| `scene-same-lane-ordered-moves` | 同 sceneId 多调用方投递递增序号移动 | 验证同 lane 完成顺序和最终坐标 | 计划 |
| `scene-multi-lane-parallel-moves` | 多 sceneId 同时移动 | 观察多 lane 并行、executor 竞争和隔离性 | 计划 |
| `scene-query-entity` | 不同实体数下查询单一 uid | 观察 scene lane 只读消息和 Map get 成本 | 计划 |
| `scene-list-entities-snapshot` | 1/64/1024/16384 实体执行列表快照 | 观察 List.copyOf、复制分配和快照大小 | 计划 |
| `scene-enter-move-leave-loop` | 每实体 enter -> N move -> query -> leave | 观察完整本地生命周期、清空 scene Map 和结果完成成本 | 计划 |
| `scene-generated-dispatcher-move` | DTO encode -> generated dispatcher -> BO -> SceneService | 隔离协议/dispatcher/BO 包装，不与基础 handler 混算 | 计划 |
| `scene-observability-wrapper-cost` | 相同业务链路分别关闭/开启测试侧日志和指标包装 | 观察外层观测成本；不修改正式字段或标签 | 计划 |
| `scene-sync-linear-visibility-query` | scene-sync 模板按 viewRange 线性扫描可见实体 | 仅观察 prototype O(N) 查询，不代表正式 AOI | 计划 |
| `future-aoi-broadcast-budget` | AOI index、delta、广播扇出和客户端同步 | 仅保留结果字段，正式模块/协议确认前不可运行 | 预留 |

基础 workload 至少覆盖 sceneCount 1、8、64，entitiesPerScene 1、64、1024、16384，moveCount 1、1000、100000，并发调用方 1、8、64。多 lane workload 必须使用不同 sceneId；同 lane 顺序 workload 必须携带 harness 序号并验证最终状态，不能只统计 future 完成数量。

snapshot workload 必须单独报告实体数、复制分配和结果集合大小；scene-sync 可见性 workload 必须报告扫描候选数和 viewRange。未来 AOI/broadcast workload 在正式 API、状态版本、协议和广播语义确认前保持 `reserved-not-runnable`。

## 4. 环境元数据

后续正式结果至少记录以下字段：

| 字段 | 说明 | 示例 |
| --- | --- | --- |
| `evidenceId` | 本次证据编号 | `scene-move-loop-20260710-local-win` |
| `track` | 性能 track | `scene-move-loop` |
| `javaVersion` | Java 版本 | `21.0.x` |
| `jvmFlags` | JVM 参数 | `-Xms2g -Xmx2g` |
| `sceneMode` | 测量层级 | `request-only` / `local-scene` / `generated-scene-sync` |
| `schedulerMode` | Actor 调度器 | `local-direct` / `executor-single` / `executor-fixed` |
| `executionDomain` | 执行域来源 | `direct-test` / `starter-managed` |
| `sceneCount` | 场景数量 | `1` / `8` / `64` |
| `laneCount` | 实际 scene lane 数 | `1` / `8` / `64` |
| `entitiesPerScene` | 每场景实体数 | `1` / `64` / `1024` / `16384` |
| `moveCount` | 每轮移动数量 | `1` / `1000` / `100000` |
| `concurrentCallers` | 同时调用方数量 | `1` / `8` / `64` |
| `existingEntityRatio` | move 前已 enter 的比例 | `0` / `0.5` / `1.0` |
| `coordinatePattern` | 坐标输入分布 | `sequential` / `uniform` / `hot-cell` |
| `positionRange` | 坐标范围 | `[-10000,10000]` |
| `snapshotEveryMoves` | 每多少次 move 做快照 | `0` / `100` / `1000` |
| `snapshotEntityCount` | 快照实体数量 | `1024` |
| `generatedDispatcherEnabled` | 是否经过 generated dispatcher | `false` / `true` |
| `visibilityQueryMode` | 可见性实现 | `none` / `template-linear` / `future-aoi` |
| `viewRange` | prototype 可见性范围 | `4` / `16` / `64` |
| `observabilityMode` | 日志/指标包装 | `none` / `test-wrapper` / `template-handler` |
| `traceIdMode` | traceId 生成方式 | `fixed` / `unique-per-move` |
| `warmupRounds` | 预热轮数 | `5` |
| `measureRounds` | 测量轮数 | `10` |
| `thresholdMode` | 阈值模式 | `none` |

Executor 结果还必须记录 executor 类型、线程数、线程名、队列实现和是否与其他执行域共享；generated 结果必须记录协议 DTO 大小、编码字节数和 codegen 版本。敏感玩家数据不得进入报告。

## 5. 测量指标

| 指标 | 说明 |
| --- | --- |
| `operationThroughputPerSecond` | 完成场景操作数/秒 |
| `enterCompletionAvgNs` | enter 从调用到结果 future 完成的平均延迟 |
| `moveCompletionAvgNs` | moveWithResult 完成平均延迟 |
| `moveCompletionP50Ns` | move 完成 p50 延迟 |
| `moveCompletionP95Ns` | move 完成 p95 延迟 |
| `moveCompletionP99Ns` | move 完成 p99 延迟 |
| `dispatchLatencyNs` | gateway/Actor dispatch 调用成本 |
| `handlerLatencyNs` | scene handler 内状态读写成本；需 harness 分层观测 |
| `queryEntityAvgNs` | 单实体查询完成延迟 |
| `listSnapshotAvgNs` | listEntities 快照完成延迟 |
| `snapshotEntityCount` | 实际返回快照实体数 |
| `snapshotAllocatedBytes` | 单次列表快照估算分配量 |
| `sameLaneOrderViolationCount` | 同 scene lane 序号或最终状态顺序错误数，必须为 0 |
| `previousStateMismatchCount` | move previous/current 与预期不一致数，必须为 0 |
| `createdOnMoveCount` | 缺失实体直接 move 后创建状态的次数 |
| `laneBacklogPeak` | 测量期间 lane 待处理消息峰值；无观测时必须说明 |
| `visibilityQueryAvgNs` | scene-sync prototype 可见性查询平均延迟 |
| `visibleCandidateScanCount` | 可见性查询实际扫描候选数 |
| `logMetricOverheadNs` | 测试侧或模板 handler 日志/指标包装增量 |
| `allocationBytesPerMove` | 每次 move 的对象分配量 |
| `completionFailureCount` | 调度或 handler 异常完成数 |
| `crossActorDirectMutationCount` | 绕过消息直接修改其他 Actor 状态次数，必须为 0 |
| `resultMismatchCount` | 最终位置、实体数、快照或可见集不一致数，必须为 0 |

吞吐必须与尾延迟、scene/lane 数、实体数、调度器和 workload 同时报告。没有可靠 handler/backlog 观测时必须写明 `not-collected`，不能用 0 伪装。日志/指标增量只能在相同 workload、相同 executor 和相同 traceId 策略下比较。

## 6. 结果格式

每条结果至少包含以下字段：

```text
evidenceId | workloadId | sceneMode | schedulerMode | executionDomain
sceneCount | laneCount | entitiesPerScene | moveCount | concurrentCallers
existingEntityRatio | coordinatePattern | positionRange
snapshotEveryMoves | snapshotEntityCount | generatedDispatcherEnabled
visibilityQueryMode | viewRange | observabilityMode | traceIdMode
sampleCount | operationThroughputPerSecond | enterCompletionAvgNs
moveCompletionAvgNs | moveCompletionP50Ns | moveCompletionP95Ns
moveCompletionP99Ns | dispatchLatencyNs | handlerLatencyNs
queryEntityAvgNs | listSnapshotAvgNs | snapshotAllocatedBytes
sameLaneOrderViolationCount | previousStateMismatchCount | createdOnMoveCount
laneBacklogPeak | visibilityQueryAvgNs | visibleCandidateScanCount
logMetricOverheadNs | allocationBytesPerMove | completionFailureCount
crossActorDirectMutationCount | resultMismatchCount | thresholdMode | notes
```

首轮报告必须保留原始命令、Maven 解析版本、JVM 参数、机器信息、warmup 和每轮样本。request-only、基础 SceneService、两种 scheduler、generated dispatcher、scene-sync linear visibility 和未来 AOI 预留必须分表，不能混合计算一个“场景移动平均延迟”。

## 7. 风险边界

以下动作必须单独建档并等待用户确认：

- 新增 JMH、benchmark Maven profile、场景压测、运行模板/示例、故障注入、长稳或 CI 门禁。
- 修改 `SceneService`、`LocalSceneService`、Scene record、Actor/Game/Player/Cache API、SPI 或 ErrorCode。
- 修改 scene lane 归属、Actor scheduler、执行域、队列、线程池、completion 或跨 Actor 消息边界。
- 修改 enter/move/query/list/leave、缺失实体 create-on-move、previous/current 或快照顺序/可变性语义。
- 创建或冻结 AOI、状态同步、stateVersion、snapshot/delta、广播、客户端协议或跨服迁移。
- 修改协议 ID、DSL、codegen、缓存策略、日志字段、指标标签或 TraceId 语义。
- 定义移动延迟、scene size、tick、AOI、广播、吞吐或生产容量阈值。
- 根据结果修改场景容器、快照、Actor 投递或热路径。

本 readiness 不新增 JMH，不定义性能阈值，不修改 SceneService 或 Actor API，不修改 lane、线程模型和移动/快照语义，不实现 AOI 或广播，不修改协议、缓存、日志或指标，不运行场景服务，也不作为生产容量承诺。

## 8. 后续推进

```text
检查本页负载矩阵与采集口径
  -> 用户确认 benchmark/profile/harness 观测边界
  -> 独立 benchmark 任务建档
  -> request + LocalSceneService + LocalActorScheduler 基线
  -> ExecutorActorScheduler 分层基线
  -> generated dispatcher 与观测包装分层基线
  -> 复用已有 AOI/state-sync 本地模块并评审新增测量边界
  -> 正式 AOI / delta / broadcast 独立证据
  -> 故障注入和长稳证据
  -> 结果审阅
  -> 如需 API、Actor、容器、快照或热路径调整，再次高风险确认
```

当前只完成 readiness。正式 benchmark、场景容量、真实 AOI/广播证据、阈值、CI 门禁和实现优化均未完成。
