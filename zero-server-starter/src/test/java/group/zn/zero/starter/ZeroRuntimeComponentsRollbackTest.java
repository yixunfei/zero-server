package group.zn.zero.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.core.lifecycle.LifecycleState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link ZeroRuntimeComponents} 启动回滚契约测试。
 *
 * @author zn
 */
class ZeroRuntimeComponentsRollbackTest {

    /**
     * 验证启动中途失败时，仅按逆序停止已成功启动的组件，并将全部停止失败保留为主异常的 suppressed。
     */
    @Test
    void startFailureShouldRollbackEveryStartedComponentAndSuppressStopFailuresInReverseOrder() {
        List<String> steps = new ArrayList<>();
        ZeroException startFailure = ZeroException.of(
                SystemErrorCode.SYSTEM_ERROR,
                "startup-primary",
                null);
        AssertionError secondStopFailure = new AssertionError("stop-second");
        IllegalStateException firstStopFailure = new IllegalStateException("stop-first");
        RecordingLifecycle first = new RecordingLifecycle(
                "first", steps, null, firstStopFailure);
        RecordingLifecycle second = new RecordingLifecycle(
                "second", steps, null, secondStopFailure);
        RecordingLifecycle failing = new RecordingLifecycle(
                "failing", steps, startFailure, null);
        RecordingLifecycle neverStarted = new RecordingLifecycle(
                "never-started", steps, null, null);
        ZeroRuntimeComponents components = runtimeWith(List.of(first, second, failing, neverStarted));

        ZeroException actual = assertThrows(ZeroException.class, components::start);

        assertSame(startFailure, actual);
        assertEquals(LifecycleState.FAILED, components.state());
        assertEquals(List.of(
                "start:first",
                "start:second",
                "start:failing",
                "stop:second",
                "stop:first"), steps);
        assertEquals(2, actual.getSuppressed().length);
        assertSame(secondStopFailure, actual.getSuppressed()[0]);
        assertSame(firstStopFailure, actual.getSuppressed()[1]);
    }

    /**
     * 验证聚合组件重复停止时不会再次调用子组件，保持继承生命周期状态机提供的幂等边界。
     */
    @Test
    void repeatedStopShouldBeIdempotent() {
        List<String> steps = new ArrayList<>();
        RecordingLifecycle component = new RecordingLifecycle(
                "component", steps, null, null);
        ZeroRuntimeComponents components = runtimeWith(List.of(component));

        components.start();
        components.stop();
        components.stop();

        assertEquals(List.of("start:component", "stop:component"), steps);
        assertEquals(LifecycleState.STOPPED, components.state());
    }

    /**
     * 验证正常停止遇到 {@link Error} 时仍会逆序尝试全部组件，并把后续失败附加到首个失败。
     */
    @Test
    void stopShouldContinueAfterErrorAndAggregateLaterFailures() {
        List<String> steps = new ArrayList<>();
        RecordingLifecycle first = new RecordingLifecycle(
                "first", steps, null, null);
        IllegalStateException secondFailure = new IllegalStateException("stop-second");
        RecordingLifecycle second = new RecordingLifecycle(
                "second", steps, null, secondFailure);
        AssertionError thirdFailure = new AssertionError("stop-third");
        RecordingLifecycle third = new RecordingLifecycle(
                "third", steps, null, thirdFailure);
        ZeroRuntimeComponents components = runtimeWith(List.of(first, second, third));
        components.start();

        ZeroException actual = assertThrows(ZeroException.class, components::stop);

        assertSame(thirdFailure, actual.getCause());
        assertEquals(List.of(
                "start:first",
                "start:second",
                "start:third",
                "stop:third",
                "stop:second",
                "stop:first"), steps);
        assertEquals(0, thirdFailure.getSuppressed().length);
        assertEquals(1, actual.getSuppressed().length);
        assertSame(secondFailure, actual.getSuppressed()[0]);
        assertEquals(LifecycleState.FAILED, components.state());
    }

    /**
     * 验证启动回滚会关闭 runtime 拥有的全部执行器，避免失败路径遗留工作线程。
     */
    @Test
    void startFailureShouldCloseOwnedExecutors() {
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype(
                "rollback-owned-executor",
                1);
        ZeroException startFailure = ZeroException.of(
                SystemErrorCode.SYSTEM_ERROR,
                "startup-primary",
                null);
        RecordingLifecycle failing = new RecordingLifecycle(
                "failing",
                new ArrayList<>(),
                startFailure,
                null);
        ZeroRuntimeComponents components = ZeroRuntimeFactory
                .localBuilder(new MapZeroConfig(Map.of("zero.mode", "test")))
                .executors(executors)
                .lifecycleComponents(List.of(failing))
                .build();

        ZeroException actual = assertThrows(ZeroException.class, components::start);

        assertSame(startFailure, actual);
        assertExecutorShutdown(executors.logicExecutor());
        assertExecutorShutdown(executors.actorExecutor());
        assertExecutorShutdown(executors.remoteIoExecutor());
        assertExecutorShutdown(executors.backgroundExecutor());
    }

    /**
     * 验证 raw Error 启动失败作为统一 lifecycle 异常的主 cause 保持原对象，并按发生顺序聚合全部组件回滚
     * 与执行器关闭失败；Starter 层不执行 production 异常脱敏或重新分类。
     *
     * @throws InterruptedException 等待测试执行器任务进入阻塞状态时当前测试线程被中断。
     */
    @Test
    void rawErrorStartFailureShouldAggregateRollbackAndExecutorFailuresOnPrimaryCause()
            throws InterruptedException {
        List<String> steps = new ArrayList<>();
        IllegalStateException firstStopFailure = new IllegalStateException("stop-first");
        AssertionError secondStopFailure = new AssertionError("stop-second");
        AssertionError startFailure = new AssertionError("start-primary");
        RecordingLifecycle first = new RecordingLifecycle(
                "first", steps, null, firstStopFailure);
        RecordingLifecycle second = new RecordingLifecycle(
                "second", steps, null, secondStopFailure);
        RecordingLifecycle failing = new RecordingLifecycle(
                "failing", steps, startFailure, null);
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype(
                "raw-error-rollback-executor",
                1);
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        executors.logicExecutor().execute(() -> {
            taskStarted.countDown();
            try {
                releaseTask.await();
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(taskStarted.await(5, TimeUnit.SECONDS));
        ZeroRuntimeComponents components = ZeroRuntimeFactory
                .localBuilder(new MapZeroConfig(Map.of("zero.mode", "test")))
                .executors(executors)
                .lifecycleComponents(List.of(first, second, failing))
                .build();

        try {
            Thread.currentThread().interrupt();
            ZeroException actual = assertThrows(ZeroException.class, components::start);

            assertSame(startFailure, actual.getCause());
            assertEquals(LifecycleState.FAILED, components.state());
            assertEquals(List.of(
                    "start:first",
                    "start:second",
                    "start:failing",
                    "stop:second",
                    "stop:first"), steps);
            assertEquals(0, startFailure.getSuppressed().length);
            assertEquals(3, actual.getSuppressed().length);
            assertSame(secondStopFailure, actual.getSuppressed()[0]);
            assertSame(firstStopFailure, actual.getSuppressed()[1]);
            ZeroException executorFailure = (ZeroException) actual.getSuppressed()[2];
            assertSame(SystemErrorCode.SYSTEM_ERROR, executorFailure.errorCode());
            assertEquals("close runtime executor interrupted", executorFailure.message());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            releaseTask.countDown();
            executors.close();
        }
    }

    /**
     * 创建仅包含指定生命周期组件的测试运行时。
     *
     * @param lifecycleComponents 生命周期组件；不为 {@code null}，保持传入顺序。
     * @return 测试运行时；不为 {@code null}，调用方负责停止。
     */
    private ZeroRuntimeComponents runtimeWith(final List<Lifecycle> lifecycleComponents) {
        return ZeroRuntimeFactory.localBuilder(new MapZeroConfig(Map.of("zero.mode", "test")))
                .lifecycleComponents(lifecycleComponents)
                .build();
    }

    /**
     * 断言 starter 暴露的执行器已进入 shutdown 状态。
     *
     * @param executor 待检查执行器；必须由 {@link ZeroRuntimeExecutors#localPrototype(String, int)} 创建。
     */
    private void assertExecutorShutdown(final java.util.concurrent.Executor executor) {
        ExecutorService executorService = (ExecutorService) executor;
        assertTrue(executorService.isShutdown());
    }

    /**
     * 可精确注入启动与停止失败的测试生命周期组件。
     *
     * @author zn
     */
    private static final class RecordingLifecycle implements Lifecycle {

        /**
         * 组件名称。
         */
        private final String name;

        /**
         * 有序步骤记录。
         */
        private final List<String> steps;

        /**
         * 启动时抛出的异常；为 {@code null} 时启动成功。
         */
        private final Throwable startFailure;

        /**
         * 停止时抛出的异常；为 {@code null} 时停止成功。
         */
        private final Throwable stopFailure;

        /**
         * 当前生命周期状态。
         */
        private LifecycleState state = LifecycleState.NEW;

        /**
         * 创建测试生命周期组件。
         *
         * @param name 组件名称；不为 {@code null}。
         * @param steps 有序步骤记录；不为 {@code null}，仅在测试线程访问。
         * @param startFailure 启动失败；可为 {@code null}。
         * @param stopFailure 停止失败；可为 {@code null}。
         */
        private RecordingLifecycle(
                final String name,
                final List<String> steps,
                final Throwable startFailure,
                final Throwable stopFailure) {
            this.name = name;
            this.steps = steps;
            this.startFailure = startFailure;
            this.stopFailure = stopFailure;
        }

        /**
         * 返回测试组件当前状态。
         *
         * @return 当前状态；不为 {@code null}，仅在测试线程访问。
         */
        @Override
        public LifecycleState state() {
            return state;
        }

        /**
         * 记录启动动作，并按需抛出预设失败；仅在测试线程调用，不修改外部业务数据。
         *
         * @throws RuntimeException 当预设启动失败为运行时异常时抛出。
         * @throws Error 当预设启动失败为错误时抛出。
         */
        @Override
        public void start() {
            steps.add("start:" + name);
            if (startFailure != null) {
                state = LifecycleState.FAILED;
                throwUnchecked(startFailure);
            }
            state = LifecycleState.RUNNING;
        }

        /**
         * 记录停止动作，并按需抛出预设失败；仅在测试线程调用，不修改外部业务数据。
         *
         * @throws RuntimeException 当预设停止失败为运行时异常时抛出。
         * @throws Error 当预设停止失败为错误时抛出。
         */
        @Override
        public void stop() {
            steps.add("stop:" + name);
            if (stopFailure != null) {
                state = LifecycleState.FAILED;
                throwUnchecked(stopFailure);
            }
            state = LifecycleState.STOPPED;
        }

        /**
         * 抛出测试预设的非受检失败。
         *
         * @param failure 预设失败；不为 {@code null}。
         * @throws RuntimeException 当失败为运行时异常时抛出。
         * @throws Error 当失败为错误时抛出。
         */
        private void throwUnchecked(final Throwable failure) {
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new AssertionError("Test failure must be unchecked", failure);
        }
    }
}
