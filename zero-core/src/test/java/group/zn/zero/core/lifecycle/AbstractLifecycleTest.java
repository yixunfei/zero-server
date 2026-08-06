package group.zn.zero.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import org.junit.jupiter.api.Test;

/**
 * 生命周期基类测试。
 *
 * @author zn
 */
class AbstractLifecycleTest {

    /**
     * 验证生命周期按启动和停止状态流转。
     */
    @Test
    void lifecycleShouldMoveThroughRunningAndStopped() {
        CountingLifecycle lifecycle = new CountingLifecycle(false);

        assertEquals(LifecycleState.NEW, lifecycle.state());

        lifecycle.start();
        lifecycle.start();

        assertTrue(lifecycle.running());
        assertEquals(1, lifecycle.startCount);

        lifecycle.stop();
        lifecycle.stop();

        assertFalse(lifecycle.running());
        assertEquals(LifecycleState.STOPPED, lifecycle.state());
        assertEquals(1, lifecycle.stopCount);
    }

    /**
     * 验证启动失败后状态进入失败态并保留错误码。
     */
    @Test
    void lifecycleShouldMoveToFailedWhenStartFails() {
        CountingLifecycle lifecycle = new CountingLifecycle(true);

        ZeroException ex = assertThrows(ZeroException.class, lifecycle::start);

        assertEquals(SystemErrorCode.SYSTEM_ERROR, ex.errorCode());
        assertEquals(LifecycleState.FAILED, lifecycle.state());
    }

    /**
     * 验证普通运行时异常会包装为统一异常。
     */
    @Test
    void lifecycleShouldWrapRuntimeExceptionWithZeroException() {
        RuntimeFailureLifecycle lifecycle = new RuntimeFailureLifecycle();

        ZeroException ex = assertThrows(ZeroException.class, lifecycle::start);

        assertEquals(SystemErrorCode.SYSTEM_ERROR, ex.errorCode());
        assertEquals("raw failure", ex.getCause().getMessage());
        assertEquals(LifecycleState.FAILED, lifecycle.state());
    }

    /**
     * 测试用生命周期组件。
     */
    private static final class CountingLifecycle extends AbstractLifecycle {

        /**
         * 是否启动失败。
         */
        private final boolean failOnStart;

        /**
         * 启动次数。
         */
        private int startCount;

        /**
         * 停止次数。
         */
        private int stopCount;

        /**
         * 创建测试组件。
         *
         * @param failOnStart true 表示启动失败。
         */
        private CountingLifecycle(final boolean failOnStart) {
            this.failOnStart = failOnStart;
        }

        /**
         * 执行启动逻辑。
         *
         * @throws ZeroException 当配置为启动失败时抛出。
         */
        @Override
        protected void doStart() {
            if (failOnStart) {
                throw ZeroException.of(SystemErrorCode.SYSTEM_ERROR, "start failed", null);
            }
            startCount++;
        }

        /**
         * 执行停止逻辑。
         */
        @Override
        protected void doStop() {
            stopCount++;
        }
    }

    /**
     * 抛出普通运行时异常的测试组件。
     */
    private static final class RuntimeFailureLifecycle extends AbstractLifecycle {

        /**
         * 执行启动逻辑。
         *
         * @throws RuntimeException 固定抛出普通运行时异常。
         */
        @Override
        protected void doStart() {
            throw new RuntimeException("raw failure");
        }
    }
}
