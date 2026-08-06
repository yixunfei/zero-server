package group.zn.zero.examples.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * 本地 CSV 配置热重载示例闭环测试。
 *
 * @author zn
 */
class ConfigHotReloadLocalApplicationTest {

    /**
     * CFHR-12：验证示例完成 v1、合法 v2、非法失败保旧和审计日志闭环。
     *
     * @throws IOException 当示例临时文件操作失败时抛出。
     */
    @Test
    void shouldRunCompleteLocalHotReloadFlow() throws IOException {
        ConfigHotReloadLocalApplication.DemoResult result = ConfigHotReloadLocalApplication.runDemo();

        assertEquals(1, result.initialVersion());
        assertEquals(2, result.loadedVersion());
        assertEquals(2, result.rejectedVersion());
        assertEquals("Steel Sword", result.retainedName());
        assertTrue(result.auditRecords() >= 4);
    }
}
