package group.zn.zero.examples.scheduler;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 本地受管定时任务示例闭环测试。
 *
 * @author zn
 */
class ManagedSchedulerLocalApplicationTest {

    /**
     * MSTR-18：验证三类任务、失败策略、Actor、取消、观测和停止闭环。
     */
    @Test
    void shouldRunCompleteManagedSchedulerFlow() {
        ManagedSchedulerLocalApplication.DemoResult result =
                ManagedSchedulerLocalApplication.runDemo();

        assertAll(
                () -> assertTrue(result.onceCompleted()),
                () -> assertTrue(result.fixedDelayCompleted()),
                () -> assertTrue(result.cancelIdempotent()),
                () -> assertTrue(result.fixedRateSkipped()),
                () -> assertTrue(result.remoteIoThread()),
                () -> assertTrue(result.defaultFailureStopped()),
                () -> assertTrue(result.continueRecovered()),
                () -> assertTrue(result.actorUpdated()),
                () -> assertTrue(result.traceForwarded()),
                () -> assertTrue(result.actorThread()),
                () -> assertTrue(result.schedulerLogsPresent()),
                () -> assertTrue(result.schedulerMetricsPresent()),
                () -> assertTrue(result.runtimeStopped()));
    }
}
