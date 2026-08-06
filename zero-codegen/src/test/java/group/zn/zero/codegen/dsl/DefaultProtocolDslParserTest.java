package group.zn.zero.codegen.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.codegen.model.ProtocolField;
import group.zn.zero.codegen.model.ProtocolTypeKind;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.protocol.ProtocolFeature;
import org.junit.jupiter.api.Test;

/**
 * 默认协议 DSL 解析器测试。
 *
 * @author zn
 */
class DefaultProtocolDslParserTest {

    /**
     * 验证 DSL 可以解析枚举、消息、协议、方法和嵌套集合类型。
     */
    @Test
    void parserShouldParseDocumentModel() {
        ProtocolDslDocument document = new DefaultProtocolDslParser().parse(sampleDsl());

        assertEquals("group.zn.zero.demo", document.namespace());
        assertEquals(1, document.enums().size());
        assertEquals(2, document.messages().size());
        assertEquals(1, document.protocols().size());
        assertEquals(1, document.methods().size());
        assertEquals(1001, document.protocols().get(0).id());
        assertEquals(ProtocolDirection.CLIENT_TO_SERVER, document.protocols().get(0).direction());
        assertTrue(document.protocols().get(0).features().contains(ProtocolFeature.COMPRESSED));

        ProtocolField token = document.messages().get(0).fields().get(1);
        assertEquals("token", token.name());
        assertTrue(token.nullable());
        assertEquals(ProtocolTypeKind.SCALAR, token.type().kind());

        ProtocolField attrs = document.messages().get(0).fields().get(2);
        assertTrue(attrs.compatible());
        assertEquals(ProtocolTypeKind.LIST, attrs.type().kind());
        assertEquals(ProtocolTypeKind.MAP, attrs.type().arguments().get(0).kind());

        ProtocolField matrix = document.messages().get(0).fields().get(3);
        assertEquals(ProtocolTypeKind.ARRAY, matrix.type().kind());
        assertEquals(ProtocolTypeKind.ARRAY, matrix.type().arguments().get(0).kind());

        ProtocolField nullableNumbers = document.messages().get(0).fields().get(4);
        assertEquals(ProtocolTypeKind.LIST, nullableNumbers.type().kind());
        assertEquals(ProtocolTypeKind.OPTIONAL, nullableNumbers.type().arguments().get(0).kind());
    }

    /**
     * 验证重复协议号会在 DSL 校验阶段失败。
     */
    @Test
    void duplicateProtocolIdShouldFail() {
        String dsl = """
                namespace group.zn.zero.demo

                message Ping {
                  field 1 int id
                }

                protocol Ping {
                  id 1001
                  direction client_to_server
                }

                protocol Pong {
                  id 1001
                  direction server_to_client
                }
                """;

        assertThrows(ZeroException.class, () -> new DefaultProtocolDslParser().parse(dsl));
    }

    /**
     * 验证对外字段类型不允许直接使用 null 占位。
     */
    @Test
    void nullFieldTypeShouldFail() {
        String dsl = """
                namespace group.zn.zero.demo

                message Broken {
                  field 1 null value
                }
                """;

        assertThrows(ZeroException.class, () -> new DefaultProtocolDslParser().parse(dsl));
    }

    private String sampleDsl() {
        return """
                namespace group.zn.zero.demo

                enum ItemType {
                  UNKNOWN = 0
                  WEAPON = 1
                }

                message LoginRequest {
                  field 1 int playerId // 玩家 ID
                  field 2 optional<string> token
                  field 3 List<Map<int, string>> attrs compatible
                  field 4 int[][] matrix
                  field 5 List<optional<int>> nullableNumbers
                  field 6 ItemType itemType
                }

                message LoginResponse {
                  field 1 boolean ok
                  field 2 nullable string message
                }

                protocol LoginRequest {
                  id 1001
                  direction client_to_server
                  version 1
                  feature compressed
                }

                method login {
                  protocol LoginRequest
                  request LoginRequest
                  response LoginResponse
                  bo LoginEventBO
                  comment 登录请求
                }
                """;
    }
}
