package group.zn.zero.examples.scheduler;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.monitor.MonitorRuntime;
import group.zn.zero.runtime.actor.ActorRuntime;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeConfigKeys;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.monitor.MonitorRuntimeComponent;
import group.zn.zero.starter.LocalRuntime;
import group.zn.zero.starter.LocalRuntimeBuilder;
import group.zn.zero.starter.scheduler.LocalManagedScheduler;
import group.zn.zero.starter.scheduler.LoggingScheduledTaskObserver;
import group.zn.zero.starter.ZeroManagedSchedulerFactory;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 本地受管定时任务 minimum-slice 可运行入口。
 *
 * <p>示例显式启用 scheduler，复用 Starter 管理的 background、remote IO 和 Actor 执行域，
 * 运行三类任务、失败策略、取消与 Actor dispatch，最后通过 runtime 生命周期停止 timer 和执行器。</p>
 *
 * @author zn
 */
public final class ManagedSchedulerLocalApplication {

    private ManagedSchedulerLocalApplication() {
    }

    /**
     * 运行示例并输出可稳定断言的摘要。
     *
     * <p>参数当前未使用；方法只修改进程内示例状态，不访问 Docker、网络或持久化数据。</p>
     *
     * @param args 命令行参数；可为空数组。
     */
    public static void main(final String[] args) {
        DemoResult result = runDemo();
        System.out.println("managed-scheduler=ok"
                + "|once=" + result.onceCompleted()
                + "|fixedDelay=" + result.fixedDelayCompleted()
                + "|fixedRateSkip=" + result.fixedRateSkipped()
                + "|defaultFailureStopped=" + result.defaultFailureStopped()
                + "|continueRecovered=" + result.continueRecovered()
                + "|actor=" + result.actorUpdated()
                + "|trace=" + result.traceForwarded()
                + "|remoteIo=" + result.remoteIoThread()
                + "|cancel=" + result.cancelIdempotent()
                + "|logs=" + result.schedulerLogsPresent()
                + "|metrics=" + result.schedulerMetricsPresent()
                + "|stopped=" + result.runtimeStopped());
    }

    /**
     * 运行本地 scheduler 闭环并返回测试结果。
     *
     * <p>方法创建 Starter 受管执行器和 timer，完成任务后显式停止 runtime；所有周期任务在停止前
     * 已取消。方法非线程安全，不修改外部数据。</p>
     *
     * @return 不可变示例结果；不可为空，线程安全。
     * @throws RuntimeException 当装配、任务执行或停止失败时抛出，具体框架失败绑定 ErrorCode。
     */
    public static DemoResult runDemo() {
        InMemoryLogSink terminalLogSink = new InMemoryLogSink();
        MonitorRuntime monitorRuntime = MonitorRuntime.createDefault();
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("managed-scheduler-example", 2);
        GameRuntime runtime = null;
        boolean stopped = false;
        try {
            RuntimeAssembly assembly = assemble(terminalLogSink, monitorRuntime, executors);
            runtime = assembly.runtime();
            runtime.start();
            ManagedSchedulerDemoTasks.TaskSuiteResult tasks = new ManagedSchedulerDemoTasks(
                    assembly.scheduler(),
                    runtime.require(ActorRuntime.ACTOR_SCHEDULER),
                    executors.remoteIoExecutor()).run();
            runtime.stop();
            stopped = runtime.state() == LifecycleState.STOPPED;
            ObservabilityResult observability = inspectObservability(terminalLogSink, monitorRuntime);
            return toDemoResult(tasks, observability, stopped);
        } finally {
            if (!stopped) {
                closeRuntime(runtime, executors);
            }
        }
    }

    private static RuntimeAssembly assemble(
            final InMemoryLogSink terminalLogSink,
            final MonitorRuntime monitorRuntime,
            final ZeroRuntimeExecutors executors) {
        ZeroConfig config = new MapZeroConfig(Map.of(
                ZeroRuntimeConfigKeys.ZERO_NAME, "managed-scheduler-local",
                ZeroRuntimeConfigKeys.SCHEDULER_ENABLED, "true",
                ZeroRuntimeConfigKeys.SCHEDULER_MAX_TASKS, "32",
                ZeroRuntimeConfigKeys.SCHEDULER_MAX_IN_FLIGHT, "8",
                ZeroRuntimeConfigKeys.SCHEDULER_STOP_TIMEOUT_MILLIS, "3000",
                ZeroRuntimeConfigKeys.SCHEDULER_THREAD_NAME_PREFIX, "managed-scheduler-example-timer"));
        LocalRuntimeBuilder builder = LocalRuntime
                .builder(config, terminalLogSink, executors)
                .replace(MonitorRuntimeComponent.MONITOR_RUNTIME, monitorRuntime);
        LocalManagedScheduler scheduler = ZeroManagedSchedulerFactory
                .configure(builder, config, builder.logAppender(), monitorRuntime, executors)
                .orElseThrow();
        return new RuntimeAssembly(builder.build(), scheduler);
    }

    private static ObservabilityResult inspectObservability(
            final InMemoryLogSink terminalLogSink,
            final MonitorRuntime monitorRuntime) {
        boolean logsPresent = terminalLogSink.records().stream()
                .anyMatch(record -> "zero-scheduler".equals(record.module())
                        && record.errorCode() != null
                        && record.fields().containsKey("taskId"));
        Set<String> metricNames = monitorRuntime.registry().samples().stream()
                .map(sample -> sample.name())
                .collect(Collectors.toUnmodifiableSet());
        boolean metricsPresent = metricNames.contains(LoggingScheduledTaskObserver.EXECUTION_TOTAL)
                && metricNames.contains(LoggingScheduledTaskObserver.EXECUTION_DURATION_MILLIS)
                && metricNames.contains(LoggingScheduledTaskObserver.SKIPPED_TOTAL);
        return new ObservabilityResult(logsPresent, metricsPresent);
    }

    private static DemoResult toDemoResult(
            final ManagedSchedulerDemoTasks.TaskSuiteResult tasks,
            final ObservabilityResult observability,
            final boolean stopped) {
        return new DemoResult(
                tasks.onceCompleted(),
                tasks.fixedDelayCompleted(),
                tasks.cancelIdempotent(),
                tasks.fixedRateSkipped(),
                tasks.remoteIoThread(),
                tasks.defaultFailureStopped(),
                tasks.continueRecovered(),
                tasks.actorUpdated(),
                tasks.traceForwarded(),
                tasks.actorThread(),
                observability.logsPresent(),
                observability.metricsPresent(),
                stopped);
    }

    private static void closeRuntime(
            final GameRuntime runtime,
            final ZeroRuntimeExecutors executors) {
        if (runtime == null) {
            executors.close();
        } else {
            runtime.close();
        }
    }

    /**
     * 示例可验证结果。
     *
     * @param onceCompleted once 是否只完成一次。
     * @param fixedDelayCompleted fixed-delay 是否至少完成两次。
     * @param cancelIdempotent 取消是否幂等。
     * @param fixedRateSkipped fixed-rate 是否观察到运行中跳过。
     * @param remoteIoThread 异步模拟是否运行在 remote IO 执行域。
     * @param defaultFailureStopped 默认失败策略是否终止任务。
     * @param continueRecovered CONTINUE 是否恢复下一周期。
     * @param actorUpdated 状态是否由 Actor handler 更新。
     * @param traceForwarded traceId 是否传递到 ActorMessage。
     * @param actorThread Actor handler 是否运行在 Actor 执行域。
     * @param schedulerLogsPresent 是否产生 scheduler 结构化日志。
     * @param schedulerMetricsPresent 是否产生 scheduler 低基数指标。
     * @param runtimeStopped runtime 是否完成停止。
     * @author zn
     */
    public record DemoResult(
            boolean onceCompleted,
            boolean fixedDelayCompleted,
            boolean cancelIdempotent,
            boolean fixedRateSkipped,
            boolean remoteIoThread,
            boolean defaultFailureStopped,
            boolean continueRecovered,
            boolean actorUpdated,
            boolean traceForwarded,
            boolean actorThread,
            boolean schedulerLogsPresent,
            boolean schedulerMetricsPresent,
            boolean runtimeStopped) {
    }

    /**
     * 示例内部 runtime 组合。
     *
     * @param runtime 中立运行时。
     * @param scheduler 本地受管调度器。
     * @author zn
     */
    private record RuntimeAssembly(
            GameRuntime runtime,
            LocalManagedScheduler scheduler) {
    }

    /**
     * 示例观测结果。
     *
     * @param logsPresent scheduler 结构化日志是否存在。
     * @param metricsPresent scheduler 最小指标是否存在。
     * @author zn
     */
    private record ObservabilityResult(boolean logsPresent, boolean metricsPresent) {
    }
}
