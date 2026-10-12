package group.zn.zero.examples.recipes.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 本地发现筛选和健康切换回归，不证明网络可达性。 @author zn */
class DiscoveryExampleTest {
    /** 不可用、不同集群和不同标签实例不能参与业务选择。 */
    @Test
    void selectsHealthyLogicThenSwitchesAndUnregisters() {
        var result = DiscoveryExample.runFlow();
        assertEquals(List.of("logic-a"), result.selected());
        assertEquals(0, result.healthyAfterOffline());
        assertEquals(List.of("logic-c"), result.replacement());
        assertTrue(result.emptyAfterUnregister());
    }
}
