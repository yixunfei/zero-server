package group.zn.zero.protocol.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import org.junit.jupiter.api.Test;

/**
 * 内存协议注册表测试。
 *
 * @author zn
 */
class InMemoryProtocolRegistryTest {

    /**
     * 验证协议可以注册并按 ID 或名称查找。
     */
    @Test
    void registryShouldFindByIdAndName() {
        InMemoryProtocolRegistry registry = new InMemoryProtocolRegistry();
        ProtocolDefinition definition = new ProtocolDefinition(
                1001,
                "player.query",
                ProtocolDirection.CLIENT_TO_SERVER,
                1);

        registry.register(definition);

        assertEquals(definition, registry.findById(1001).orElseThrow());
        assertEquals(definition, registry.findByName("player.query").orElseThrow());
        assertTrue(registry.findById(1002).isEmpty());
    }

    /**
     * 验证协议 ID 冲突会被拒绝。
     */
    @Test
    void duplicateIdShouldFail() {
        InMemoryProtocolRegistry registry = new InMemoryProtocolRegistry();
        registry.register(new ProtocolDefinition(1001, "player.query", ProtocolDirection.CLIENT_TO_SERVER, 1));

        assertThrows(ZeroException.class, () -> registry.register(
                new ProtocolDefinition(1001, "player.other", ProtocolDirection.CLIENT_TO_SERVER, 1)));
    }

    /**
     * 验证协议名称冲突会被拒绝。
     */
    @Test
    void duplicateNameShouldFail() {
        InMemoryProtocolRegistry registry = new InMemoryProtocolRegistry();
        registry.register(new ProtocolDefinition(1001, "player.query", ProtocolDirection.CLIENT_TO_SERVER, 1));

        assertThrows(ZeroException.class, () -> registry.register(
                new ProtocolDefinition(1002, "player.query", ProtocolDirection.CLIENT_TO_SERVER, 1)));
    }
}
