package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.lifecycle.LifecycleState;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import org.junit.jupiter.api.Test;

/**
 * {@link ManagedCloseableLifecycle} 关闭状态机测试。
 *
 * @author zn
 */
class ManagedCloseableLifecycleTest {

    /** 用于反证底层关闭异常图不会穿透安全边界的敏感哨兵。 */
    private static final String SECRET = "PAF1-MANAGED-CLOSE-SECRET-SENTINEL";

    /**
     * 验证成功关闭后，重复 stop 和 close 不会再次调用底层资源。
     */
    @Test
    void successfulCloseShouldBeIdempotentAcrossLifecycleAndCloseEntryPoints() {
        ScriptedCloseable closeable = new ScriptedCloseable();
        ManagedCloseableLifecycle lifecycle = new ManagedCloseableLifecycle("redis-data", closeable);

        lifecycle.start();
        lifecycle.stop();
        lifecycle.stop();
        lifecycle.close();

        assertEquals(1, closeable.closeCalls());
        assertEquals(LifecycleState.STOPPED, lifecycle.state());
    }

    /**
     * 验证首次关闭失败不会永久标记 closed，后续可重试，且公开异常图不保留原始 Throwable。
     */
    @Test
    void failedCloseShouldRemainRetryableAndExposeOnlySanitizedFailureGraph() {
        IllegalStateException rawCause = new IllegalStateException(SECRET + "-cause");
        IllegalArgumentException rawFailure = new IllegalArgumentException(SECRET + "-primary", rawCause);
        rawFailure.addSuppressed(new AssertionError(SECRET + "-suppressed"));
        ScriptedCloseable closeable = new ScriptedCloseable(rawFailure);
        ManagedCloseableLifecycle lifecycle = new ManagedCloseableLifecycle("postgresql-data", closeable);
        lifecycle.start();

        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                lifecycle::stop);

        assertEquals("postgresql-data", failure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.CLOSE, failure.failurePhase());
        assertSame(ProductionAdapterErrorCode.CLOSE_FAILED, failure.errorCode());
        assertEquals(ProductionAdapterErrorCode.CLOSE_FAILED.message(), failure.message());
        assertNull(failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        ProductionAdapterException safeSuppressed = assertInstanceOf(
                ProductionAdapterException.class,
                failure.getSuppressed()[0]);
        assertNull(safeSuppressed.getCause());
        assertFalse(stackTrace(failure).contains(SECRET), stackTrace(failure));
        assertEquals(LifecycleState.FAILED, lifecycle.state());

        lifecycle.stop();
        lifecycle.stop();
        lifecycle.close();

        assertEquals(2, closeable.closeCalls());
        assertEquals(LifecycleState.STOPPED, lifecycle.state());
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
     * 按调用顺序抛出预设失败、失败耗尽后成功的测试资源。
     *
     * @author zn
     */
    private static final class ScriptedCloseable implements AutoCloseable {

        /** 尚未消费的关闭失败，仅由测试线程访问。 */
        private final Deque<Throwable> failures;

        /** 底层 close 实际调用次数。 */
        private int closeCalls;

        /**
         * 创建测试资源。
         *
         * @param failures 依次抛出的失败；不可为空且元素不可为空。
         */
        private ScriptedCloseable(final Throwable... failures) {
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
