package group.zn.zero.examples.business;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 每日道具领取业务示例回归测试。
 *
 * @author zn
 */
class BusinessSliceApplicationTest {

    /**
     * 验证生成协议、幂等、缓存和内部事件完整闭环。
     */
    @Test
    void runDemoShouldProcessDuplicateClaimOnce() {
        BusinessSliceApplication.DemoResult result = BusinessSliceApplication.runDemo();

        assertAll("business slice result",
                () -> assertEquals(List.of(
                        DailyRewardClaimResult.Status.ACCEPTED,
                        DailyRewardClaimResult.Status.DUPLICATE), result.statuses()),
                () -> assertEquals(100, result.inventoryQuantity()),
                () -> assertEquals(1, result.eventCount()),
                () -> assertEquals(1, result.metricCount()),
                () -> assertEquals(1, result.cacheHitCount()),
                () -> assertEquals(0, result.deadLetterCount()),
                () -> assertTrue(result.summaryLine().startsWith("business-slice=ok|statuses=")));
    }
}
