package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.starter.ZeroRuntimeComponents;
import group.zn.zero.starter.ZeroRuntimeExecutors;
import group.zn.zero.starter.ZeroRuntimeFactory;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link ZeroProductionRuntime} 启动失败资源补偿测试。
 *
 * @author zn
 */
class ZeroProductionRuntimeRollbackTest {

    /** 用于反证启动和关闭原始异常图不会对外暴露的敏感哨兵。 */
    private static final String SECRET = "PAF1-RUNTIME-ROLLBACK-SECRET-SENTINEL";

    /**
     * 验证启动早期失败会逆序关闭全部 build 资源，安全聚合关闭失败并允许失败项后续重试。
     */
    @Test
    void startFailureShouldCloseBuildResourcesInReverseAndRetryOnlyFailedItems() {
        List<String> closeSteps = new ArrayList<>();
        RetryCloseable first = new RetryCloseable(
                "first",
                closeSteps,
                new IllegalStateException(SECRET + "-first-close"));
        RetryCloseable second = new RetryCloseable("second", closeSteps);
        RetryCloseable third = new RetryCloseable(
                "third",
                closeSteps,
                new AssertionError(SECRET + "-third-close"));
        IllegalArgumentException rawStartupFailure = new IllegalArgumentException(
                SECRET + "-startup",
                new IllegalStateException(SECRET + "-startup-cause"));
        ZeroProductionRuntime runtime = runtimeWith(
                new StartupFailureLifecycle(rawStartupFailure),
                List.of(first, second, third));

        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                runtime::start);

        assertEquals("production-runtime", failure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.STARTUP, failure.failurePhase());
        assertSame(ProductionAdapterErrorCode.STARTUP_FAILED, failure.errorCode());
        assertEquals(ProductionAdapterErrorCode.STARTUP_FAILED.message(), failure.message());
        assertNull(failure.getCause());
        assertEquals(List.of(
                "close:third#1",
                "close:second#1",
                "close:first#1"), closeSteps);
        assertEquals(2, failure.getSuppressed().length);
        for (Throwable suppressed : failure.getSuppressed()) {
            ProductionAdapterException safeCloseFailure = assertInstanceOf(
                    ProductionAdapterException.class,
                    suppressed);
            assertEquals("production-resource", safeCloseFailure.adapterName());
            assertEquals(ProductionAdapterFailurePhase.ROLLBACK, safeCloseFailure.failurePhase());
            assertSame(ProductionAdapterErrorCode.ROLLBACK_FAILED, safeCloseFailure.errorCode());
            assertEquals(ProductionAdapterErrorCode.ROLLBACK_FAILED.message(), safeCloseFailure.message());
            assertNull(safeCloseFailure.getCause());
        }
        assertFalse(stackTrace(failure).contains(SECRET), stackTrace(failure));

        runtime.close();
        runtime.close();

        assertEquals(List.of(
                "close:third#1",
                "close:second#1",
                "close:first#1",
                "close:third#2",
                "close:first#2"), closeSteps);
        assertEquals(2, first.closeCalls());
        assertEquals(1, second.closeCalls());
        assertEquals(2, third.closeCalls());
    }

    /**
     * 验证 runtime 真实启动链路会把 raw Error、组件逆序回滚、executor cleanup 与 build 资源失败
     * 收敛为固定安全异常图，且不遍历或发布 raw cause。
     *
     * @throws InterruptedException 等待受管执行器任务进入阻塞状态时测试线程被中断。
     */
    @Test
    void rawErrorStartShouldExposeOnlySafeRollbackAndExecutorCleanupGraph()
            throws InterruptedException {
        List<String> lifecycleSteps = new ArrayList<>();
        RecordingFailureLifecycle first = new RecordingFailureLifecycle(
                "first",
                lifecycleSteps,
                null,
                new IllegalStateException(SECRET + "-first-stop"));
        RecordingFailureLifecycle second = new RecordingFailureLifecycle(
                "second",
                lifecycleSteps,
                null,
                new AssertionError(SECRET + "-second-stop"));
        RecordingFailureLifecycle failing = new RecordingFailureLifecycle(
                "failing",
                lifecycleSteps,
                new AssertionError(SECRET + "-startup"),
                null);
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype(
                "production-raw-error-rollback",
                1);
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        executors.logicExecutor().execute(() -> awaitRelease(taskStarted, releaseTask));
        assertTrue(taskStarted.await(5, TimeUnit.SECONDS));

        ZeroRuntimeComponents components = ZeroRuntimeFactory
                .localBuilder(new MapZeroConfig(Map.of("zero.mode", "test")))
                .executors(executors)
                .lifecycleComponents(List.of(first, second, failing))
                .build();
        List<String> closeSteps = new ArrayList<>();
        RetryCloseable buildResource = new RetryCloseable(
                "build",
                closeSteps,
                new AssertionError(SECRET + "-build-close"));
        ZeroProductionRuntime runtime = runtimeWith(components, List.of(buildResource));

        try {
            Thread.currentThread().interrupt();
            ProductionAdapterException failure = assertThrows(
                    ProductionAdapterException.class,
                    runtime::start);

            assertEquals(ProductionAdapterFailurePhase.STARTUP, failure.failurePhase());
            assertSame(ProductionAdapterErrorCode.STARTUP_FAILED, failure.errorCode());
            assertNull(failure.getCause());
            assertEquals(List.of(
                    "start:first",
                    "start:second",
                    "start:failing",
                    "stop:second",
                    "stop:first"), lifecycleSteps);
            assertEquals(List.of("close:build#1"), closeSteps);
            assertEquals(4, failure.getSuppressed().length);
            for (int index = 0; index < 3; index++) {
                assertSafeRollback(failure.getSuppressed()[index], "production-runtime");
            }
            assertSafeRollback(failure.getSuppressed()[3], "production-resource");
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(stackTrace(failure).contains(SECRET), stackTrace(failure));
        } finally {
            Thread.interrupted();
            releaseTask.countDown();
            runtime.close();
            executors.close();
        }
    }

    /**
     * 验证 close-before-start 会关闭 build 资源，并永久拒绝在已关闭资源上启动 runtime。
     */
    @Test
    void closeBeforeStartShouldRejectLaterStartAndKeepResourceCloseIdempotent() {
        List<String> closeSteps = new ArrayList<>();
        RetryCloseable resource = new RetryCloseable("resource", closeSteps);
        CountingLifecycle lifecycle = new CountingLifecycle();
        ZeroProductionRuntime runtime = runtimeWith(lifecycle, List.of(resource));

        runtime.close();
        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                runtime::start);
        runtime.close();

        assertSame(ProductionAdapterErrorCode.RUNTIME_REUSE_REJECTED, failure.errorCode());
        assertEquals(ProductionAdapterFailurePhase.STARTUP, failure.failurePhase());
        assertNull(failure.getCause());
        assertEquals(0, lifecycle.startCalls());
        assertEquals(List.of("close:resource#1"), closeSteps);
        assertEquals(1, resource.closeCalls());
    }

    /**
     * 验证成功停止后的 production runtime 是 single-use，不会在已关闭持久 client 上重新启动。
     */
    @Test
    void stoppedRuntimeShouldRejectRestartWithoutTouchingClosedResources() {
        List<String> closeSteps = new ArrayList<>();
        RetryCloseable resource = new RetryCloseable("resource", closeSteps);
        CountingLifecycle lifecycle = new CountingLifecycle();
        ZeroProductionRuntime runtime = runtimeWith(lifecycle, List.of(resource));

        runtime.start();
        runtime.stop();
        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                runtime::start);
        runtime.close();

        assertSame(ProductionAdapterErrorCode.RUNTIME_REUSE_REJECTED, failure.errorCode());
        assertEquals(1, lifecycle.startCalls());
        assertEquals(1, lifecycle.stopCalls());
        assertEquals(List.of("close:resource#1"), closeSteps);
        assertEquals(1, resource.closeCalls());
    }

    /**
     * 验证运行中的第二次启动不会被通用 lifecycle 幂等分支吞掉，而是返回 production single-use 错误。
     */
    @Test
    void runningRuntimeShouldRejectSecondStartWithoutRestartingComponents() {
        List<String> closeSteps = new ArrayList<>();
        RetryCloseable resource = new RetryCloseable("resource", closeSteps);
        CountingLifecycle lifecycle = new CountingLifecycle();
        ZeroProductionRuntime runtime = runtimeWith(lifecycle, List.of(resource));

        runtime.start();
        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                runtime::start);
        runtime.close();

        assertSame(ProductionAdapterErrorCode.RUNTIME_REUSE_REJECTED, failure.errorCode());
        assertEquals(ProductionAdapterFailurePhase.STARTUP, failure.failurePhase());
        assertEquals(1, lifecycle.startCalls());
        assertEquals(1, lifecycle.stopCalls());
        assertEquals(List.of("close:resource#1"), closeSteps);
    }

    /**
     * 创建只包含启动失败组件和指定 build 资源的 production runtime。
     *
     * @param failingLifecycle 启动失败组件；不可为空。
     * @param buildCloseables build 资源，保持创建顺序；不可为空、元素不可为空。
     * @return 未启动的 production runtime；不可为空，调用方负责关闭。
     */
    private ZeroProductionRuntime runtimeWith(
            final Lifecycle failingLifecycle,
            final List<AutoCloseable> buildCloseables) {
        ZeroRuntimeComponents components = ZeroRuntimeFactory
                .localBuilder(new MapZeroConfig(Map.of("zero.mode", "test")))
                .lifecycleComponents(List.of(failingLifecycle))
                .build();
        return runtimeWith(components, buildCloseables);
    }

    /**
     * 使用指定 starter 聚合组件创建未启动的 production runtime。
     *
     * @param components starter 聚合组件；不可为空，调用方负责其生命周期。
     * @param buildCloseables build 资源，保持创建顺序；不可为空、元素不可为空。
     * @return 未启动的 production runtime；不可为空，调用方负责关闭。
     */
    private ZeroProductionRuntime runtimeWith(
            final ZeroRuntimeComponents components,
            final List<AutoCloseable> buildCloseables) {
        ProductionStartupBudget budget = new ProductionStartupBudget(
                Duration.ofSeconds(30),
                Duration.ofSeconds(5));
        ZeroProductionRuntime.RuntimeAdapters adapters = new ZeroProductionRuntime.RuntimeAdapters(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        return new ZeroProductionRuntime(
                "production",
                components,
                List.of(),
                buildCloseables,
                budget,
                adapters);
    }

    /**
     * 断言 runtime 最终异常图中的 cleanup 节点已经安全重分类。
     *
     * @param failure 待断言 cleanup 失败；不可为空。
     * @param expectedAdapter 预期稳定 Adapter 名称；不可为空。
     */
    private void assertSafeRollback(final Throwable failure, final String expectedAdapter) {
        ProductionAdapterException safeFailure = assertInstanceOf(
                ProductionAdapterException.class,
                failure);
        assertEquals(expectedAdapter, safeFailure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.ROLLBACK, safeFailure.failurePhase());
        assertSame(ProductionAdapterErrorCode.ROLLBACK_FAILED, safeFailure.errorCode());
        assertEquals(ProductionAdapterErrorCode.ROLLBACK_FAILED.message(), safeFailure.message());
        assertNull(safeFailure.getCause());
    }

    /**
     * 在受管执行器任务中等待释放，并在 shutdownNow 中断时保留中断状态退出。
     *
     * @param taskStarted 任务进入等待前的通知；不可为空。
     * @param releaseTask 允许任务正常退出的通知；不可为空。
     */
    private void awaitRelease(
            final CountDownLatch taskStarted,
            final CountDownLatch releaseTask) {
        taskStarted.countDown();
        try {
            releaseTask.await();
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 把完整异常图打印为文本，供敏感哨兵反证。
     *
     * @param failure 待打印异常；不可为空。
     * @return 完整堆栈文本；不可为空，调用方可变，方法不修改异常图。
     */
    private String stackTrace(final Throwable failure) {
        StringWriter writer = new StringWriter();
        try (PrintWriter printer = new PrintWriter(writer)) {
            failure.printStackTrace(printer);
        }
        return writer.toString();
    }

    /**
     * 记录启动/停止顺序并允许注入任意非受检失败的测试生命周期。
     *
     * @author zn
     */
    private static final class RecordingFailureLifecycle extends AbstractLifecycle {

        /** 组件稳定名称。 */
        private final String name;

        /** 有序生命周期步骤；仅由测试线程访问。 */
        private final List<String> steps;

        /** 启动失败；为空表示启动成功。 */
        private final Throwable startFailure;

        /** 停止失败；为空表示停止成功。 */
        private final Throwable stopFailure;

        /**
         * 创建可注入失败的测试生命周期。
         *
         * @param name 组件名称；不可为空。
         * @param steps 有序步骤；不可为空，仅由测试线程访问。
         * @param startFailure 启动失败；可为空。
         * @param stopFailure 停止失败；可为空。
         */
        private RecordingFailureLifecycle(
                final String name,
                final List<String> steps,
                final Throwable startFailure,
                final Throwable stopFailure) {
            this.name = name;
            this.steps = steps;
            this.startFailure = startFailure;
            this.stopFailure = stopFailure;
        }

        /** 记录启动并按需抛出预设失败，不修改外部业务状态。 */
        @Override
        protected void doStart() {
            steps.add("start:" + name);
            throwUnchecked(startFailure);
        }

        /** 记录停止并按需抛出预设失败，不修改外部业务状态。 */
        @Override
        protected void doStop() {
            steps.add("stop:" + name);
            throwUnchecked(stopFailure);
        }

        /**
         * 抛出预设非受检失败；为空时直接返回。
         *
         * @param failure 预设失败；可为空。
         * @throws RuntimeException 预设失败为运行时异常时抛出。
         * @throws Error 预设失败为严重错误时抛出。
         */
        private void throwUnchecked(final Throwable failure) {
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    /**
     * 在 doStart 中抛出预设失败的测试生命周期组件。
     *
     * @author zn
     */
    private static final class StartupFailureLifecycle extends AbstractLifecycle {

        /** 启动时抛出的预设失败。 */
        private final RuntimeException failure;

        /**
         * 创建启动失败组件。
         *
         * @param failure 预设启动失败；不可为空。
         */
        private StartupFailureLifecycle(final RuntimeException failure) {
            this.failure = failure;
        }

        /**
         * 抛出预设启动失败，不修改外部业务状态。
         *
         * @throws RuntimeException 始终抛出预设失败。
         */
        @Override
        protected void doStart() {
            throw failure;
        }
    }

    /**
     * 记录 production runtime 实际启动与停止次数的测试生命周期。
     *
     * @author zn
     */
    private static final class CountingLifecycle extends AbstractLifecycle {

        /** 实际启动次数，仅由测试线程访问。 */
        private int startCalls;

        /** 实际停止次数，仅由测试线程访问。 */
        private int stopCalls;

        /**
         * 返回实际启动次数。
         *
         * @return 非负启动次数，仅由测试线程读取。
         */
        private int startCalls() {
            return startCalls;
        }

        /**
         * 返回实际停止次数。
         *
         * @return 非负停止次数，仅由测试线程读取。
         */
        private int stopCalls() {
            return stopCalls;
        }

        /** 记录一次启动，不修改外部业务数据。 */
        @Override
        protected void doStart() {
            startCalls++;
        }

        /** 记录一次停止，不修改外部业务数据。 */
        @Override
        protected void doStop() {
            stopCalls++;
        }
    }

    /**
     * 按调用顺序抛出预设关闭失败、失败耗尽后成功的 build 资源。
     *
     * @author zn
     */
    private static final class RetryCloseable implements AutoCloseable {

        /** 资源稳定名称。 */
        private final String name;

        /** 有序关闭步骤，仅由测试线程访问。 */
        private final List<String> closeSteps;

        /** 尚未消费的关闭失败，仅由测试线程访问。 */
        private final Deque<Throwable> failures;

        /** 底层 close 实际调用次数。 */
        private int closeCalls;

        /**
         * 创建测试 build 资源。
         *
         * @param name 资源名称；不可为空。
         * @param closeSteps 有序关闭步骤；不可为空，仅由测试线程访问。
         * @param failures 依次抛出的失败；不可为空且元素不可为空。
         */
        private RetryCloseable(
                final String name,
                final List<String> closeSteps,
                final Throwable... failures) {
            this.name = name;
            this.closeSteps = closeSteps;
            this.failures = new ArrayDeque<>(Arrays.asList(failures));
        }

        /**
         * 返回底层 close 实际调用次数。
         *
         * @return 非负调用次数，仅由测试线程读取。
         */
        private int closeCalls() {
            return closeCalls;
        }

        /**
         * 记录调用并抛出下一个预设失败；预设失败耗尽后成功。
         *
         * @throws RuntimeException 下一个预设失败为运行时异常时抛出。
         * @throws Error 下一个预设失败为严重错误时抛出。
         */
        @Override
        public void close() {
            closeCalls++;
            closeSteps.add("close:" + name + "#" + closeCalls);
            Throwable failure = failures.pollFirst();
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }
}
