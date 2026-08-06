# 受管定时任务片段

把 `ManagedSchedulerModule.java` 的 `{{packageName}}` 替换为业务包名。在 Starter 装配阶段显式启用本地 scheduler：

```java
ZeroConfig config = new MapZeroConfig(Map.of(
        ZeroRuntimeConfigKeys.ZERO_NAME, "{{artifactId}}",
        ZeroRuntimeConfigKeys.SCHEDULER_ENABLED, "true"));
InMemoryLogSink terminalLogSink = new InMemoryLogSink();
MonitorRuntime monitorRuntime = MonitorRuntime.createDefault();
ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("{{artifactId}}", 4);
ZeroRuntimeBuilder builder = ZeroRuntimeFactory
        .localBuilder(config, terminalLogSink, executors)
        .monitorRuntime(monitorRuntime);
ManagedScheduler scheduler = ZeroManagedSchedulerFactory
        .configure(builder, config, builder.logAppender(), monitorRuntime, executors)
        .orElseThrow();
ZeroRuntimeComponents components = builder.build();
components.start();
```

然后创建 `ManagedSchedulerModule` 并调用 `start()`。停止时先 `module.close()`，再 `components.stop()`，与 L1 生命周期保持一致。

最小配置：

```properties
zero.scheduler.enabled=true
zero.scheduler.max-tasks=1024
zero.scheduler.max-in-flight=256
zero.scheduler.stop-timeout-ms=3000
zero.scheduler.thread-name-prefix=zero-scheduler
```

使用边界：

- `ScheduledTask` 直接返回非空 `CompletionStage<Void>`；同步动作可用 `ScheduledTasks.runnable(...)` 或 `consumer(...)`。
- fixed-delay 从 stage 真正完成后计算下次延迟。
- fixed-rate 错过或运行中的节拍会跳过，不排队、不补跑、不追赶。
- 默认失败策略是 `CANCEL_ON_FAILURE`；只有明确可容忍并具备幂等语义的任务才显式选择 `CONTINUE`。
- 远程 IO 交给受管 remote IO executor 或异步客户端，禁止在 scheduler worker 上 `join()` / `get()`。
- 玩家、场景和实体状态只能投递 Actor 消息；将 `ScheduledTaskContext.traceId()` 显式传入 `ActorMessage`。
- 本片段只适用于 local / single-process / minimum-slice，不包含 cron、持久化任务、集群协调、leader election 或分布式唯一执行。
