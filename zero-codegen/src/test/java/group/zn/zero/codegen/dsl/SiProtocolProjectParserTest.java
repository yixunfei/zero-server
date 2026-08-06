package group.zn.zero.codegen.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.ProtocolDirection;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * `.si` 协议工程解析器测试。
 *
 * @author zn
 */
class SiProtocolProjectParserTest {

    /**
     * 验证目录递归、protoId 映射、方法 DTO 和事件级 BO。
     *
     * @throws Exception 读取测试资源失败时抛出。
     */
    @Test
    void parserShouldLoadRecursiveSiProject() throws Exception {
        Path root = resource("protocol-dsl/sample");
        ProtocolDslDocument document = new SiProtocolProjectParser().parse(
                "group.zn.zero.generated",
                List.of(root),
                root.resolve("protoId.txt"));

        assertEquals("group.zn.zero.generated", document.namespace());
        assertEquals(1, document.enums().size());
        assertEquals(8, document.messages().size());
        assertEquals(5, document.protocols().size());
        assertEquals(5, document.methods().size());
        assertProtocol(document, "InventoryQueryInventoryProtocol", 3001, ProtocolDirection.CLIENT_TO_SERVER);
        assertProtocol(document, "InventoryInventorySnapshotProtocol", 4000, ProtocolDirection.SERVER_TO_CLIENT);
        assertProtocol(document, "PlayerQueryPlayerProtocol", 1001, ProtocolDirection.CLIENT_TO_SERVER);
        assertProtocol(document, "PlayerUpdateTagsProtocol", 1003, ProtocolDirection.CLIENT_TO_SERVER);
        assertProtocol(document, "PlayerQueryPlayerResultProtocol", 2000, ProtocolDirection.SERVER_TO_CLIENT);

        assertTrue(document.messages().stream().anyMatch(message -> "PlayerQueryPlayerProtocol".equals(message.name())));
        assertTrue(document.methods().stream().anyMatch(method ->
                "PlayerQueryPlayerEventBO".equals(method.boName())
                        && "queryPlayer".equals(method.methodName())
                        && !method.expandedParameters()
                        && method.parameters().size() == 2));
        assertFalse(document.methods().stream().anyMatch(method ->
                "IPlayerBO".equals(method.boName()) || "IInventoryBO".equals(method.boName())));
    }

    /**
     * 验证 protoId 中没有对应 `.si` 文件时失败，避免静默丢失区间。
     *
     * @throws Exception 读取测试资源失败时抛出。
     */
    @Test
    void unusedProtoIdSchemaShouldFail() throws Exception {
        Path root = resource("protocol-dsl/sample/game");
        Path protoId = resource("protocol-dsl/sample/protoId.txt");

        assertThrows(ZeroException.class, () -> new SiProtocolProjectParser().parse(
                "group.zn.zero.generated",
                List.of(root),
                protoId));
    }

    private Path resource(final String name) throws URISyntaxException {
        return Path.of(Thread.currentThread().getContextClassLoader().getResource(name).toURI());
    }

    private void assertProtocol(
            final ProtocolDslDocument document,
            final String name,
            final int id,
            final ProtocolDirection direction) {
        assertTrue(document.protocols().stream().anyMatch(protocol ->
                name.equals(protocol.name())
                        && protocol.id() == id
                        && protocol.direction() == direction));
    }
}
