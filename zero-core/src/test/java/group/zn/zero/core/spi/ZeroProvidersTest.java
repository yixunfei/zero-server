package group.zn.zero.core.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPI 提供者工具测试。
 *
 * @author zn
 */
class ZeroProvidersTest {

    /**
     * 验证提供者按优先级和名称稳定排序。
     */
    @Test
    void orderedShouldSortByPriorityAndName() {
        TestProvider highB = new TestProvider("b", -1);
        TestProvider highA = new TestProvider("a", -1);
        TestProvider low = new TestProvider("c", 10);

        List<TestProvider> providers = ZeroProviders.ordered(List.of(low, highB, highA));

        assertEquals(List.of(highA, highB, low), providers);
    }

    /**
     * 验证 first 返回最高优先级提供者。
     */
    @Test
    void firstShouldReturnHighestPriorityProvider() {
        TestProvider high = new TestProvider("high", -1);
        TestProvider low = new TestProvider("low", 10);

        assertEquals(high, ZeroProviders.first(List.of(low, high)).orElseThrow());
    }

    /**
     * 测试用 SPI 提供者。
     *
     * @param name 提供者名称。
     * @param priority 提供者优先级。
     * @author zn
     */
    private record TestProvider(String name, int priority) implements ZeroProvider {

        /**
         * 返回提供者名称。
         *
         * @return 提供者名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return name;
        }

        /**
         * 返回提供者优先级。
         *
         * @return 数值越小优先级越高；线程安全。
         */
        @Override
        public int priority() {
            return priority;
        }
    }
}
