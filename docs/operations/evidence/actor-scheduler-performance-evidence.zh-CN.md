# actor-scheduler 性能证据口径草案

```text
zero-performance-evidence=track|id=actor-scheduler|status=readiness|benchmarkComplete=false|productionReady=false
```

本文定义 `actor-scheduler` 首批性能证据的可复现口径草案。它服务于 zeroServer “高性能多场景处理”目标，但当前只作为后续 benchmark / JMH / 压测任务的输入，不是性能结果报告，也不是生产容量承诺。

## 1. 定位

`actor-scheduler` 覆盖 `ActorScheduler.dispatch`、lane 内顺序投递、多个 lane 的并发推进、handler 完成、异常完成、积压排空、traceId / context 传递，以及 `GameActorGateway` 从业务入口到 Actor 消息的封装路径。

当前任务只固定“测什么、怎么记录、哪些边界不能误碰”。它不修改调度器实现，不新增 benchmark profile，不定义阈值，也不把本地单机样本解释为生产容量。

## 2. 源码证据

| 证据 | 当前状态 | 用途 |
| --- | --- | --- |
| [zero-actor/pom.xml](../../../zero-actor/pom.xml) | 已有 | Actor 模块入口，依赖应保持低耦合 |
| [zero-game/pom.xml](../../../zero-game/pom.xml) | 已有 | 游戏业务执行域与 Actor gateway 入口 |
| [zero-actor/src/main/java/group/zn/zero/actor/scheduler/ActorScheduler.java](../../../zero-actor/src/main/java/group/zn/zero/actor/scheduler/ActorScheduler.java) | 已有 | 调度器公共抽象 |
| [zero-actor/src/main/java/group/zn/zero/actor/scheduler/LocalActorScheduler.java](../../../zero-actor/src/main/java/group/zn/zero/actor/scheduler/LocalActorScheduler.java) | 已有 | 本地串行 lane 调度参考实现 |
| [zero-actor/src/main/java/group/zn/zero/actor/scheduler/ExecutorActorScheduler.java](../../../zero-actor/src/main/java/group/zn/zero/actor/scheduler/ExecutorActorScheduler.java) | 已有 | executor-backed lane 调度参考实现 |
| [zero-game/src/main/java/group/zn/zero/game/GameActorGateway.java](../../../zero-game/src/main/java/group/zn/zero/game/GameActorGateway.java) | 已有 | 业务上下文、lane key、payload 到 Actor 消息的包装入口 |
| [docs/threading-model.zh-CN.md](../../../docs/threading-model.zh-CN.md) | 已有 | 线程模型与线程安全边界 |
| [docs/operations/api-compatibility-gate.zh-CN.md](../../../docs/operations/api-compatibility-gate.zh-CN.md) | 已有 | API / SPI 风险边界 |
| [docs/operations/performance.zh-CN.md](../../../docs/operations/performance.zh-CN.md) | 已有 | 性能 track 聚合入口 |

这些证据只能说明当前具备设计和源码观察点，不说明 Actor 调度性能已经达标。

## 3. workload 矩阵

| workloadId | 场景 | 目的 | 当前阶段 |
| --- | --- | --- | --- |
| `same-lane-serial-dispatch` | 同一个 `LaneKey` 连续投递 N 条消息 | 观察 lane 内顺序、dispatch 完成成本和 handler 轻量路径 | 计划 |
| `multi-lane-parallel-dispatch` | 多个 `LaneKey` 分批投递 | 观察多 lane 调度吞吐、竞争和 executor 推进成本 | 计划 |
| `async-handler-completion` | handler 返回异步 `CompletionStage` | 观察异步完成对 lane drain 的影响 | 计划 |
| `handler-exception-completion` | handler 抛出异常或返回失败 stage | 观察异常完成成本和失败传播 | 计划 |
| `burst-dispatch-backlog` | 突发投递形成短时积压 | 观察 backlog 排空和队列深度记录口径 | 计划 |
| `remote-route-gateway-stub` | 只记录远程路由 gateway stub 口径 | 预留跨节点路由观测，不触发真实 RPC / MQ | 计划 |
| `context-traceid-propagation` | 从 `GameRequestContext` 到 `ActorContext` | 观察 traceId 传递是否可被测量结果关联 | 计划 |
| `no-direct-cross-actor-mutation` | 负向设计检查 | 确认 workload 不通过直接引用修改跨 Actor 状态 | 计划 |

## 4. 环境元数据

后续正式结果至少记录以下字段：

| 字段 | 说明 | 示例 |
| --- | --- | --- |
| `evidenceId` | 本次证据编号 | `actor-scheduler-20260710-local-win` |
| `track` | 性能 track | `actor-scheduler` |
| `javaVersion` | Java 版本 | `21.0.x` |
| `osName` | 操作系统 | `Windows 11` |
| `cpuModel` | CPU 型号 | 本地机器实际值 |
| `jvmFlags` | JVM 参数 | `-Xms2g -Xmx2g` |
| `executorModel` | 调度器执行模型 | `local` / `single-executor` / `fixed-executor` |
| `laneCount` | lane 数量 | `1` / `8` / `64` |
| `messageCount` | 投递消息数量 | `100000` |
| `warmupRounds` | 预热轮数 | `5` |
| `measureRounds` | 测量轮数 | `10` |
| `sampleCount` | 样本数 | `10` |
| `thresholdMode` | 阈值模式 | `none` |

`thresholdMode=none` 表示第一阶段只记录结果，不设置通过 / 失败阈值。

## 5. 测量指标

| 指标 | 说明 |
| --- | --- |
| `dispatchLatencyNs` | 从调用 `dispatch` 到返回 completion stage 的耗时 |
| `handlerLatencyNs` | handler 自身处理耗时 |
| `completionLatencyNs` | 从投递到 completion 完成的总耗时 |
| `queueDepth` | 采样时 lane 队列深度或估算深度 |
| `backlogDrainTime` | 突发积压从峰值到排空的耗时 |
| `allocationBytesPerOp` | 每次操作估算分配字节数 |
| `contextSwitchCount` | 线程切换或线程名变化次数的观测值 |
| `failedDispatchCount` | handler 缺失、异常或调度失败次数 |

第一阶段如果无法稳定获取 `allocationBytesPerOp` 或 `contextSwitchCount`，必须在结果中写明采集方式和未采集原因，不允许用空值伪装为 0。

## 6. 结果格式

建议后续 benchmark 输出一行摘要和一份结构化结果文件：

```text
actor-scheduler-performance=ok|workloads=8|java=21|thresholdMode=none|requiresConfirmation=true
```

结构化结果建议包含：

| 字段 | 说明 |
| --- | --- |
| `summary` | 与控制台 marker 一致的摘要 |
| `environment` | 环境元数据 |
| `workloads` | workload 结果数组 |
| `measurements` | 每个 workload 的测量指标 |
| `warnings` | 未采集指标、样本不足或环境偏差 |
| `riskBoundary` | 是否仍需高风险确认 |

## 7. 风险边界

以下动作不属于本草案范围，必须单独建档并等待用户确认：

- 不新增 JMH 依赖、benchmark profile、benchmark 模块或 CI job。
- 不定义性能阈值、容量承诺、发布阻断门禁或回归判断线。
- 不修改 Actor scheduler、handler 调用语义、completion 语义或异常传播语义。
- 不修改 lane、执行域、队列容量或背压策略。
- 不新增 Maven profile，不改变默认构建行为。
- 不作为生产容量承诺，不替代压测、长稳或真实多节点验证。

尤其注意：`ActorScheduler`、`LocalActorScheduler`、`ExecutorActorScheduler`、`GameActorGateway`、lane 归属、远程 Actor 路由、线程池管理和背压策略均属于高风险边界。任何实现调整都必须回到 AGENTS.md 的高风险暂停流程。

## 8. 后续推进

推荐顺序：

```text
ZeroPerformanceBaseline --describeTrack actor-scheduler
  -> ZeroActorSchedulerBenchmarkReadiness
  -> ZeroPerformanceBaseline --confirmTrack actor-scheduler
  -> 单独任务建档
  -> 用户确认
  -> opt-in focused benchmark / JMH
  -> 只读结果归档
  -> 再讨论阈值或优化
```

本草案通过只说明“可以进入下一轮 benchmark 设计”，不说明“已经允许实现 benchmark”，更不说明“Actor 调度已经达到生产性能目标”。
