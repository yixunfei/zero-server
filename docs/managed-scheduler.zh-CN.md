# 本地受管定时任务运行时

状态：`minimum-slice / local single-process / opt-in / productionReady=false`

本文说明 zeroServer 首个通用受管定时任务切片。它解决业务模块各自创建 timer、线程池、取消逻辑和观测字段的问题，为本地单进程原型提供一致的调度入口；它不是 cron 平台、分布式任务系统或高频游戏 tick 引擎。

## 1. 能力边界

本切片提供：

- 一次性延迟任务。
- fixed-delay 周期任务。
- fixed-rate 周期任务。
- 异步 `CompletionStage<Void>` 完成契约。
- 每 handle 不重入。
- 活动任务与全局 in-flight 双重容量上限。
- 幂等取消、状态快照和 runtime 停止等待。
- ErrorCode、结构化事件、标准日志和低基数指标。
- Starter 默认关闭、显式 opt-in 的本地装配。
- Actor dispatch、远程 IO 和生命周期安全示例。

本切片明确不提供：

- cron、日历、时区或节假日表达式。
- 持久化任务、进程崩溃恢复或补偿任务表。
- 集群协调、leader election、分布式锁或全局唯一执行。
- 跨节点 fixed-rate 一致时钟。
- Netty 握手/鉴权/心跳 timer 或 Kafka RPC pending 时间轮替换。
- scene、NPC、lockstep 等高频 tick 的生产精度、容量或长稳承诺。
- 在线管理 API、GM 创建任务、审批流或远程脚本执行。

## 2. 模块与依赖

采用已确认方案 S1，不新增 Maven 模块：

```text
zero-core
  group.zn.zero.core.scheduler
    -> 中立任务、句柄、上下文、状态、事件、策略、observer、ErrorCode
    -> 不依赖日志、监控、Netty、Kafka、Nacos 或数据 Adapter

zero-server-starter
  group.zn.zero.starter.scheduler
    -> LocalManagedScheduler
    -> LocalManagedSchedulerOptions
    -> LoggingScheduledTaskObserver
  group.zn.zero.starter
    -> ZeroManagedSchedulerFactory
    -> ZeroRuntimeConfigKeys
    -> ZeroRuntimeExecutors
```

未来本地替代实现或分布式 Adapter 可以实现 `ManagedScheduler`，业务任务不需要拿到 JDK `Future`、executor 或原始 timer。

## 3. 两阶段执行模型

```text
单 timer thread
  -> 到期判断
  -> handle 状态 CAS
  -> 全局 in-flight 预算
  -> 一次性提交到受管 background executor
       -> cancel / stop 二次检查
       -> 创建 taskId / executionId / traceId / 执行时序
       -> 调用 ScheduledTask.execute(context)
       -> 跟踪非空 CompletionStage<Void> 到真正终态
       -> 记录成功或失败
       -> fixed-delay 从终态时刻重排
       -> fixed-rate 回到 monotonic cadence
       -> 恰好一次释放 in-flight permit
```

timer thread 只负责到期、状态推进、预算判断和一次性提交。它不运行用户任务、observer、日志或指标。用户任务与 observer 使用 Starter 注入的非内联 background executor；异步远程 IO 使用 remote IO 执行域或真正的异步客户端。

`ZeroManagedSchedulerFactory` 会拒绝 `ZeroRuntimeExecutors.direct()` 和当前 `singleThreaded(...)`，因为它们的 background executor 可能内联。首个本地示例使用 `ZeroRuntimeExecutors.localPrototype(...)`。

## 4. 公共 API

| 类型 | 用途 | 关键契约 |
| --- | --- | --- |
| `ManagedScheduler` | 注册三类任务、读取活动快照、管理生命周期 | 线程安全；停止后拒绝新任务 |
| `ScheduledTask` | 单次业务动作 | 返回非空 `CompletionStage<Void>`；不在 timer thread 调用 |
| `ScheduledTasks` | 同步 `Runnable` / `Consumer` 适配 | 不创建线程、不等待远程 IO |
| `ScheduledTaskDefinition` | 稳定 taskName 与失败策略 | 默认 `CANCEL_ON_FAILURE`；taskName 不放动态实体 ID |
| `ScheduledTaskContext` | 单次执行元数据 | taskId 稳定；executionId、traceId 每次执行独立 |
| `ScheduledTaskHandle` | 状态读取与取消 | `cancel()` 幂等；不 interrupt 已运行任务 |
| `ScheduledTaskSnapshot` | 不可变状态与计数 | 包含执行、成功、失败、跳过、拒绝和最近错误 |
| `ScheduledTaskObserver` | 中立观测端口 | observer 失败不改变业务任务结果 |
| `SchedulerErrorCode` | 调度错误分类 | 对外错误和错误日志统一绑定 |

最小异步契约：

```java
CompletionStage<Void> execute(ScheduledTaskContext context);
```

同步、无阻塞动作可以适配：

```java
scheduler.scheduleOnce(
        ScheduledTaskDefinition.defaults("cache-warmup"),
        Duration.ZERO,
        ScheduledTasks.runnable(cache::warmup));
```

远程 IO 必须直接返回异步完成信号：

```java
scheduler.scheduleWithFixedDelay(
        ScheduledTaskDefinition.defaults("remote-refresh"),
        Duration.ZERO,
        Duration.ofMinutes(1),
        context -> asyncGateway.refresh(context.traceId()));
```

禁止在任务中对远程调用执行 `join()`、`get()` 或无界阻塞。

## 5. 三类调度语义

### 5.1 Once

- delay 可以为零或正数。
- 成功进入 `COMPLETED`。
- 任务失败进入 `FAILED`。
- 到期时全局 in-flight 不足或 executor 拒绝时进入 `REJECTED`，不会自动重试。

### 5.2 Fixed-delay

- initialDelay 可以为零或正数，delay 必须大于零。
- 下一个 delay 从前一次返回 stage 的真实终态时刻开始。
- `execute(...)` 很快返回未完成 stage 时，handle 仍保持 `RUNNING`，in-flight permit 仍被占用。
- 容量不足后经过一个完整 delay 再尝试，不忙循环。

### 5.3 Fixed-rate

- initialDelay 可以为零或正数，period 必须大于零。
- 底层只使用一次性 `ScheduledExecutorService.schedule(...)`。
- 实现以 monotonic deadline 维护 cadence，不调用 JDK 周期调度方法。
- 同一 handle 仍在运行时到期的 occurrence 记为 `SKIPPED_RUNNING`。
- 全局 in-flight 不足时记为 `SKIPPED_CAPACITY`。
- timer 因 GC、系统休眠或调度抖动跨过多个 period 时，错过的 occurrence 聚合为一个 `SKIPPED_LATE` 事件。
- 错过的节拍不排队、不补跑、不追赶；下一次直接重排到首个严格晚于当前 monotonic 时间的 deadline。

跳过计数只在 timer thread 做原子累加，在执行完成、取消、失败或 stop 时聚合输出，避免一次长暂停产生逐 occurrence 对象和日志风暴。

## 6. 失败、取消与状态

周期任务默认失败策略为 `CANCEL_ON_FAILURE`。只有任务能够容忍单次丢失、具备明确幂等语义且业务已经接受继续运行时，才显式选择 `CONTINUE`：

```java
ScheduledTaskDefinition definition = new ScheduledTaskDefinition(
        "best-effort-refresh",
        ScheduledTaskFailurePolicy.CONTINUE);
```

`CONTINUE` 不是立即重试：fixed-delay 等待完整 delay，fixed-rate 回到后续 cadence。同步抛出、null stage 和 stage 异常完成走同一失败路径。业务 `ZeroException` 保留原 ErrorCode；普通异常绑定 `TASK_EXECUTION_FAILED`。

`Error` 是终止性信号：同步 `Error` 在记录终态后重新抛出；异步 stage 以 `Error` 完成时，即使策略为 `CONTINUE` 也终止周期任务。

公共状态：

```text
SCHEDULED -> RUNNING -> SCHEDULED        周期成功并重排
SCHEDULED -> RUNNING -> COMPLETED        once 成功
SCHEDULED -> RUNNING -> FAILED           失败终止
SCHEDULED -> REJECTED                    once 被拒绝
SCHEDULED -> CANCELLED                   执行前取消
RUNNING   -> CANCELLED                   当前 stage 完成后取消未来调度
```

`cancel()` 只阻止尚未进入用户代码的执行和未来周期。它不会 interrupt 已进入用户代码的任务，也不会强制取消业务返回的未知 `CompletionStage`。worker 进入用户代码的 CAS 是取消竞争的线性化点；终态与 permit 释放都有单次完成保护。

## 7. 容量与性能边界

两级预算分别限制：

- `max-tasks`：活动 handle 数量。
- `max-in-flight`：已提交 wrapper、执行中的任务和未完成异步 stage 总数。

本地实现使用单 timer resource，并开启 remove-on-cancel。默认容量只是开发期保护值，不是生产吞吐承诺。当前没有 JMH、JFR、长稳、GC 长暂停、系统休眠、数十万任务或高频 tick 容量证据。

`LocalManagedScheduler.java` 是当前最小状态机实现，后续增加 cron、持久化或分布式职责前必须先拆分设计，不能继续把新职责堆入该文件。

## 8. Starter 配置

所有配置默认值：

| 配置键 | 默认值 | 说明 |
| --- | ---: | --- |
| `zero.scheduler.enabled` | `false` | 必须显式设置 `true` 才创建本地 scheduler |
| `zero.scheduler.max-tasks` | `1024` | 正整数，活动 handle 上限 |
| `zero.scheduler.max-in-flight` | `256` | 正整数，完整异步生命周期在途上限 |
| `zero.scheduler.stop-timeout-ms` | `3000` | 正长整数，停止等待在途 stage 的毫秒数 |
| `zero.scheduler.thread-name-prefix` | `zero-scheduler` | 非空 timer 线程名前缀，控制字符被拒绝 |

布尔配置只接受忽略大小写的 `true` / `false`。本切片只有一个 timer thread，这是实现不变量，不暴露没有实际语义的 `timer-threads=1` 配置。

装配示例：

```java
MonitorRuntime monitorRuntime = MonitorRuntime.createDefault();
ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("game", 4);
ZeroRuntimeBuilder builder = ZeroRuntimeFactory
        .localBuilder(config, logSink, executors)
        .monitorRuntime(monitorRuntime);
ManagedScheduler scheduler = ZeroManagedSchedulerFactory
        .configure(builder, config, logSink, monitorRuntime, executors)
        .orElseThrow();
ZeroRuntimeComponents components = builder.build();
components.start();
```

关闭配置时，工厂返回 `Optional.empty()`，不修改 builder、不注册指标、不创建 timer。

## 9. 生命周期 L1

默认 Starter 生命周期顺序：

```text
启动：
scheduler 等基础设施
  -> PersistenceManager
  -> 普通附加业务组件

停止：
普通附加业务组件
  -> PersistenceManager
  -> scheduler
  -> ZeroRuntimeExecutors
```

业务生命周期组件应在自己的 stop 中先取消持有的任务句柄。Scheduler stop 随后关闭 admission、取消未来 timer、等待 in-flight stage，最后关闭单 timer resource。超过 `stop-timeout-ms` 时抛出绑定 `STOP_TIMEOUT` 的异常并产生 `STOP_TIMEOUT` 结构化事件；runtime 仍会尝试停止后续资源。

`ZeroRuntimeBuilder.lifecycleComponents(...)` 是高级完整顺序覆盖入口。调用后由调用方提供全部生命周期顺序，不再自动插入默认 L1 列表。

## 10. Actor 与远程 IO 边界

Scheduler worker 不能直接修改 player、scene 或 entity 状态。正确方式是显式传递 traceId 并返回 Actor dispatch stage：

```java
context -> actorScheduler.dispatch(new ActorMessage(
        context.executionId(),
        LaneKey.player(playerId),
        context.traceId(),
        new PlayerDeadlineReached()))
```

状态修改发生在目标 Actor handler，scheduler 会等待该 dispatch stage 完成，再决定任务成功、失败或 fixed-delay 的下一次排期。

远程 IO completion 同样不能直接修改 Actor 状态。推荐链路：

```text
scheduler background task
  -> 返回 async remote IO stage
  -> thenCompose 投递 ActorMessage
  -> Actor lane 修改状态
  -> 整条 stage 完成
```

## 11. 日志、指标与审计字段

Starter observer 为每个控制或执行事件写入 `zero-scheduler` 标准日志。公共审计字段包括：

- `taskName`
- `taskId`
- `scheduleType`
- `result`
- `errorCode`
- `occurrenceCount`
- `runSequence`
- `durationMillis`
- `reason`
- `executionId`（仅实际执行事件）
- `scheduledAt`（仅实际执行事件）
- `startedAt`（仅实际执行事件）
- `delayMillis`（仅实际执行事件）
- `completedAt`（执行成功或失败）

无实际业务执行的 skip、cancel、reject 和 stop 事件不伪造 executionId，使用独立 observation traceId。日志不调用用户 task 的 `toString()`，taskName 也不得包含玩家 ID、场景 ID、token、密码或完整业务参数。

最小指标：

| 指标 | 标签 |
| --- | --- |
| `zero_scheduler_execution_total` | `scheduleType`, `result` |
| `zero_scheduler_execution_duration_ms` | `scheduleType`, `result` |
| `zero_scheduler_skipped_total` | `scheduleType`, `reason` |
| `zero_scheduler_rejected_total` | `scheduleType`, `reason` |

taskName、taskId、executionId、traceId 和业务实体标识不进入指标标签。`active_tasks` 与 `in_flight` 暂未实现，避免在当前事件契约上生成不准确 gauge。

结构化事件类型：`REGISTERED`、`STARTED`、`SUCCEEDED`、`FAILED`、`SKIPPED_RUNNING`、`SKIPPED_CAPACITY`、`SKIPPED_LATE`、`REJECTED`、`STOP_TIMEOUT`、`CANCELLED`。

## 12. ErrorCode

| ErrorCode | 语义 |
| --- | --- |
| `INVALID_ARGUMENT` | 调度参数非法 |
| `INVALID_OPTIONS` | Starter 配置或执行器能力非法 |
| `NOT_RUNNING` | scheduler 未运行或已经停止接收 |
| `TASK_LIMIT_EXCEEDED` | 活动任务达到上限 |
| `TIMER_REJECTED` | timer 无法安排一次性触发 |
| `EXECUTOR_REJECTED` | background executor 拒绝用户任务 |
| `TASK_EXECUTION_FAILED` | 非统一业务异常或任务契约错误 |
| `OBSERVER_FAILED` | observer 失败并被隔离 |
| `STOP_TIMEOUT` | 停止等待在途 stage 超时 |

## 13. 可运行示例与脚手架

先安装当前仓库 SNAPSHOT，再运行独立示例：

```powershell
mvn -DskipTests install
mvn -f examples/managed-scheduler-local/pom.xml test exec:java
```

稳定摘要：

```text
managed-scheduler=ok|once=true|fixedDelay=true|fixedRateSkip=true|defaultFailureStopped=true|continueRecovered=true|actor=true|trace=true|remoteIo=true|cancel=true|logs=true|metrics=true|stopped=true
```

示例展示 once、fixed-delay、fixed-rate 慢任务 skip、默认失败终止、显式 `CONTINUE`、Actor dispatch、取消、异步 remote IO、日志、指标和 runtime stop。

可复制片段位于：

```text
templates/managed-scheduler-snippet/
```

片段不改变七种完整玩法模板清单，也不实现具体活动、排行榜或 NPC 规则。

## 14. 验证与剩余风险

本切片的 core / Starter focused tests 使用手动 one-shot timer、受控 monotonic 时间、手动 executor 和受控 `CompletableFuture`，覆盖取消竞争、异步完成、skip 聚合、容量、失败、observer 隔离、L1 顺序、stop timeout、线程边界、Actor trace 传递和 timer 释放。真实本地示例另做端到端 smoke。

以下内容仍未验证，不能从 minimum-slice 测试推断为已完成：

- Docker 或外部中间件联调。
- 分布式任务、leader election 和全局唯一执行。
- 任务持久化与进程崩溃恢复。
- JMH、JFR、生产容量、GC 长暂停和长稳。
- 高频 scene/NPC/lockstep tick 精度。
- 线上 Prometheus endpoint、dashboard、告警和运维闭环。
- virtual thread pinning 与第三方阻塞客户端兼容性。

因此本能力当前保持 `local single-process / minimum-slice / productionReady=false`。
