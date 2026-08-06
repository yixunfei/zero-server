package group.zn.zero.codegen.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * protoId 文件解析器测试。
 *
 * @author zn
 */
class ProtocolIdFileParserTest {

    /**
     * 验证 protoId 可以解析 schema 区间、大小写 key 和注释。
     */
    @Test
    void parserShouldReadRangesAndComments() {
        Map<String, ProtocolIdRange> ranges = new ProtocolIdFileParser().parse("""
                # schema c2s s2c
                Player 1000 2000
                Inventory 3000 4000 // inventory ids
                """);

        assertEquals(2, ranges.size());
        assertEquals(1000, ranges.get("player").clientToServerStart());
        assertEquals(2000, ranges.get("player").serverToClientStart());
        assertEquals(3000, ranges.get("inventory").clientToServerStart());
    }

    /**
     * 验证重复 schema 会失败，避免协议号区间歧义。
     */
    @Test
    void duplicateSchemaShouldFail() {
        String source = """
                Player 1000 2000
                player 3000 4000
                """;

        assertThrows(ZeroException.class, () -> new ProtocolIdFileParser().parse(source));
    }
}
