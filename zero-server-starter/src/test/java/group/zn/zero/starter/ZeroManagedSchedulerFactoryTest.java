package group.zn.zero.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.core.scheduler.ScheduledTaskDefinition;
import group.zn.zero.core.scheduler.ScheduledTaskHandle;
import group.zn.zero.core.scheduler.ScheduledTaskState;
import group.zn.zero.core.scheduler.SchedulerErrorCode;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.log.LogSink;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.monitor.MonitorRuntime;
import group.zn.zero.runtime.actor.ActorRuntime;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.api.RuntimeLifecycleCapabilities;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeConfigKeys;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.monitor.MonitorRuntimeComponent;
import group.zn.zero.starter.scheduler.LocalManagedScheduler;
import group.zn.zero.starter.scheduler.LoggingScheduledTaskObserver;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Starter scheduler opt-in、L1 生命周期、真实线程隔离和 Actor dispatch focused tests。
 *
 * @author zn
 */
class ZeroManagedSchedulerFactoryTest {

    /**
     * 验证默认关闭不修改 builder、不注册指标且不要求受管线程。
     */
    @Test
    void shouldRemainDisabledByDefault() {
        ZeroConfig config = config(Map.of());
        InMemoryLogSink logSink = new InMemoryLogSink();
        MonitorRuntime monitor = MonitorRuntime.createDefault();
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.direct();
        LocalRuntimeBuilder builder = LocalRuntime.builder(config, logSink, executors);
        int definitionsBefore = monitor.registry().definitions().size();

        Optional<LocalManagedScheduler> scheduler = ZeroManagedSchedulerFactory.configure(
                builder,
                config,
                builder.logAppender(),
                monitor,
                executors);

        assertTrue(scheduler.isEmpty());
        assertEquals(definitionsBefore, monitor.registry().definitions().size());
        GameRuntime components = builder.build();
        assertTrue(components.requireAll(RuntimeLifecycleCapabilities.INFRASTRUCTURE_LIFECYCLES).isEmpty());
        assertTrue(components.requireAll(RuntimeLifecycleCapabilities.APPLICATION_LIFECYCLES).isEmpty());
        components.start();
        components.stop();
    }

    /**
     * 验证启用时拒绝 direct 与兼容 demo 的内联 background executor。
     */
    @Test
    void shouldRejectInlineBackgroundExecutors() {
        ZeroConfig config = enabledConfig(Map.of());
        InMemoryLogSink logSink = new InMemoryLogSink();
        MonitorRuntime monitor = MonitorRuntime.createDefault();

        ZeroRuntimeExecutors direct = ZeroRuntimeExecutors.direct();
        LocalRuntimeBuilder directBuilder = LocalRuntime.builder(config, logSink, direct);
        ZeroException directFailure = assertThrows(
                ZeroException.class,
                () -> ZeroManagedSchedulerFactory.configure(
                        directBuilder,
                        config,
                        directBuilder.logAppender(),
                        monitor,
                        direct));
        assertSame(SchedulerErrorCode.INVALID_OPTIONS, directFailure.errorCode());

        ZeroRuntimeExecutors single = ZeroRuntimeExecutors.singleThreaded("scheduler-inline");
        try {
            LocalRuntimeBuilder singleBuilder = LocalRuntime.builder(config, logSink, single);
            ZeroException singleFailure = assertThrows(
                    ZeroException.class,
                    () -> ZeroManagedSchedulerFactory.configure(
                            singleBuilder,
                            config,
                            singleBuilder.logAppender(),
                            monitor,
                            single));
            assertSame(SchedulerErrorCode.INVALID_OPTIONS, singleFailure.errorCode());
        } finally {
            single.close();
        }
    }

    /**
     * 验证非法布尔、容量、超时和线程名前缀全部绑定 Scheduler ErrorCode。
     */
    @Test
    void shouldRejectInvalidOptions() {
        assertInvalid(Map.of(ZeroRuntimeConfigKeys.SCHEDULER_ENABLED, "yes"));
        assertInvalid(enabledConfigValues(Map.of(ZeroRuntimeConfigKeys.SCHEDULER_MAX_TASKS, "0")));
        assertInvalid(enabledConfigValues(Map.of(ZeroRuntimeConfigKeys.SCHEDULER_MAX_IN_FLIGHT, "many")));
        assertInvalid(enabledConfigValues(Map.of(
                ZeroRuntimeConfigKeys.SCHEDULER_STOP_TIMEOUT_MILLIS,
                Long.toString(Long.MAX_VALUE))));
        assertInvalid(enabledConfigValues(Map.of(ZeroRuntimeConfigKeys.SCHEDULER_THREAD_NAME_PREFIX, " ")));
    }

    /**
     * 验证 L1 顺序、真实 timer/background 隔离、日志指标以及 stop 后线程释放。
     *
     * @throws InterruptedException 当测试等待被中断时抛出。
     */
    @Test
    void shouldRunOnManagedBackgroundAndHonorL1Lifecycle() throws InterruptedException {
        ZeroConfig config = enabledConfig(Map.of(
                ZeroRuntimeConfigKeys.SCHEDULER_THREAD_NAME_PREFIX, "scheduler-l1"));
        LatchLogSink logSink = new LatchLogSink(3);
        MonitorRuntime monitor = MonitorRuntime.createDefault();
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("scheduler-factory", 2);
        LocalRuntimeBuilder builder = LocalRuntime.builder(config, logSink, executors)
                .replace(MonitorRuntimeComponent.MONITOR_RUNTIME, monitor);
        LocalManagedScheduler scheduler = ZeroManagedSchedulerFactory.configure(
                builder,
                config,
                builder.logAppender(),
                monitor,
                executors).orElseThrow();
        LifecycleProbe business = new LifecycleProbe(scheduler);
        builder.addApplicationLifecycle(ComponentId.of("test.scheduler.business"), business);
        GameRuntime components = builder.build();
        assertSame(scheduler,
                components.requireAll(RuntimeLifecycleCapabilities.INFRASTRUCTURE_LIFECYCLES).getFirst());
        assertSame(business,
                components.requireAll(RuntimeLifecycleCapabilities.APPLICATION_LIFECYCLES).getLast());
        CountDownLatch taskDone = new CountDownLatch(1);
        AtomicReference<String> workerThread = new AtomicReference<>();

        components.start();
        try {
            scheduler.scheduleOnce(
                    ScheduledTaskDefinition.defaults("thread-boundary"),
                    Duration.ZERO,
                    context -> {
                        workerThread.set(Thread.currentThread().getName());
                        taskDone.countDown();
                        return CompletableFuture.completedFuture(null);
                    });
            assertTrue(taskDone.await(3L, TimeUnit.SECONDS));
            assertTrue(logSink.await(3L, TimeUnit.SECONDS));
            assertTrue(workerThread.get().startsWith("scheduler-factory-background-"));
            assertFalse(workerThread.get().startsWith("scheduler-l1-timer-"));
            assertTrue(logSink.threads().stream()
                    .allMatch(name -> name.startsWith("scheduler-factory-background-")));
            assertTrue(monitor.registry().definitions().stream()
                    .anyMatch(definition -> LoggingScheduledTaskObserver.EXECUTION_TOTAL.equals(definition.name())));
        } finally {
            components.stop();
        }
        assertTrue(business.startedWithScheduler.get());
        assertTrue(business.stoppedWithScheduler.get());
        assertSame(LifecycleState.STOPPED, scheduler.state());
        assertThreadsStopped("scheduler-l1-timer-");
    }

    /**
     * 验证 scheduler 任务显式传递 traceId 到 ActorMessage，并等待目标 lane 完成。
     *
     * @throws InterruptedException 当测试等待被中断时抛出。
     */
    @Test
    void shouldDispatchStateMutationToActorLane() throws InterruptedException {
        ZeroConfig config = enabledConfig(Map.of());
        InMemoryLogSink logSink = new InMemoryLogSink();
        MonitorRuntime monitor = MonitorRuntime.createDefault();
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("scheduler-actor", 2);
        LocalRuntimeBuilder builder = LocalRuntime.builder(config, logSink, executors)
                .replace(MonitorRuntimeComponent.MONITOR_RUNTIME, monitor);
        LocalManagedScheduler scheduler = ZeroManagedSchedulerFactory.configure(
                builder,
                config,
                builder.logAppender(),
                monitor,
                executors).orElseThrow();
        GameRuntime components = builder.build();
        CountDownLatch actorDone = new CountDownLatch(1);
        AtomicReference<String> schedulerTrace = new AtomicReference<>();
        AtomicReference<String> actorTrace = new AtomicReference<>();
        AtomicReference<String> actorThread = new AtomicReference<>();
        AtomicBoolean stateChanged = new AtomicBoolean();
        components.require(ActorRuntime.ACTOR_SCHEDULER)
                .register(ActorMutation.class, ActorHandler.sync((context, message) -> {
            actorTrace.set(message.traceId());
            actorThread.set(Thread.currentThread().getName());
            stateChanged.set(true);
            actorDone.countDown();
        }));

        components.start();
        try {
            ScheduledTaskHandle handle = scheduler.scheduleOnce(
                    ScheduledTaskDefinition.defaults("actor-dispatch"),
                    Duration.ZERO,
                    context -> {
                        schedulerTrace.set(context.traceId());
                        return components.require(ActorRuntime.ACTOR_SCHEDULER).dispatch(new ActorMessage(
                                context.executionId(),
                                LaneKey.player("example-player"),
                                context.traceId(),
                                new ActorMutation()));
                    });
            assertTrue(actorDone.await(3L, TimeUnit.SECONDS));
            awaitState(handle, ScheduledTaskState.COMPLETED);
            assertTrue(stateChanged.get());
            assertEquals(schedulerTrace.get(), actorTrace.get());
            assertTrue(actorThread.get().startsWith("scheduler-actor-actor-"));
            assertFalse(actorThread.get().startsWith("scheduler-actor-background-"));
        } finally {
            components.stop();
        }
    }

    private static void assertInvalid(final Map<String, String> values) {
        ZeroConfig config = config(values);
        InMemoryLogSink logSink = new InMemoryLogSink();
        MonitorRuntime monitor = MonitorRuntime.createDefault();
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("invalid-scheduler", 2);
        try {
            LocalRuntimeBuilder builder = LocalRuntime.builder(config, logSink, executors);
            ZeroException failure = assertThrows(
                    ZeroException.class,
                    () -> ZeroManagedSchedulerFactory.configure(
                            builder,
                            config,
                            builder.logAppender(),
                            monitor,
                            executors));
            assertSame(SchedulerErrorCode.INVALID_OPTIONS, failure.errorCode());
        } finally {
            executors.close();
        }
    }

    private static ZeroConfig enabledConfig(final Map<String, String> overrides) {
        return config(enabledConfigValues(overrides));
    }

    private static Map<String, String> enabledConfigValues(final Map<String, String> overrides) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        values.put(ZeroRuntimeConfigKeys.SCHEDULER_ENABLED, "true");
        values.putAll(overrides);
        return Map.copyOf(values);
    }

    private static ZeroConfig config(final Map<String, String> values) {
        return new MapZeroConfig(values);
    }

    private static void awaitState(
            final ScheduledTaskHandle handle,
            final ScheduledTaskState expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
        while (handle.snapshot().state() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertSame(expected, handle.snapshot().state());
    }

    private static void assertThreadsStopped(final String prefix) throws InterruptedException {
        // awaitTermination 可在线程退出前的清理尾部返回；仍要求实际线程在有界时间内退出。
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith(prefix)) {
                assertTrue(thread.join(Duration.ofSeconds(3)), "timer thread did not exit: " + thread.getName());
            }
        }
    }

    /**
     * 测试用 Actor 状态修改消息。
     *
     * @author zn
     */
    private record ActorMutation() {
    }

    /**
     * 断言业务生命周期两侧均能使用 scheduler 的探针。
     *
     * @author zn
     */
    private static final class LifecycleProbe extends AbstractLifecycle {

        /**
         * 被测 scheduler。
         */
        private final LocalManagedScheduler scheduler;

        /**
         * 业务启动时 scheduler 是否已经运行。
         */
        private final AtomicBoolean startedWithScheduler = new AtomicBoolean();

        /**
         * 业务停止时 scheduler 是否仍在运行。
         */
        private final AtomicBoolean stoppedWithScheduler = new AtomicBoolean();

        private LifecycleProbe(final LocalManagedScheduler scheduler) {
            this.scheduler = scheduler;
        }

        @Override
        protected void doStart() {
            startedWithScheduler.set(scheduler.state() == LifecycleState.RUNNING);
        }

        @Override
        protected void doStop() {
            stoppedWithScheduler.set(scheduler.state() == LifecycleState.RUNNING);
        }
    }

    /**
     * 可等待指定日志数量并记录实际线程的测试 sink。
     *
     * @author zn
     */
    private static final class LatchLogSink implements LogSink {

        /**
         * 内存日志委托。
         */
        private final InMemoryLogSink delegate = new InMemoryLogSink();

        /**
         * 预期日志门闩。
         */
        private final CountDownLatch latch;

        /**
         * 写日志线程名称。
         */
        private final List<String> threads = new java.util.concurrent.CopyOnWriteArrayList<>();

        private LatchLogSink(final int expectedRecords) {
            latch = new CountDownLatch(expectedRecords);
        }

        @Override
        public void append(final ZeroLogRecord record) {
            threads.add(Thread.currentThread().getName());
            delegate.append(record);
            latch.countDown();
        }

        private boolean await(final long timeout, final TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        private List<String> threads() {
            return List.copyOf(threads);
        }
    }
}
