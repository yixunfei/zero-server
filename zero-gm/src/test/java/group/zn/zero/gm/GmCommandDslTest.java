package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * GM 指令 DSL 测试。
 *
 * @author zn
 */
class GmCommandDslTest {

    /**
     * 验证普通命令和位置参数会按顺序解析。
     */
    @Test
    void parserShouldSplitCommandPathAndArguments() {
        GmParsedCommand parsedCommand = GmCommandDsl.parse("/mail send player-1 item-2 10");

        assertEquals(List.of("mail", "send", "player-1", "item-2", "10"), parsedCommand.tokens());
        assertEquals("/mail send player-1 item-2 10", parsedCommand.rawText());
    }

    /**
     * 验证双引号参数支持空格、空字符串和转义引号。
     */
    @Test
    void parserShouldKeepQuotedArguments() {
        GmParsedCommand parsedCommand = GmCommandDsl.parse("/player ban player-1 \"bad words\" \"\" \"a\\\"b\"");

        assertEquals(List.of("player", "ban", "player-1", "bad words", "", "a\"b"), parsedCommand.tokens());
    }

    /**
     * 验证空 DSL、缺少前缀和未闭合引号会被拒绝。
     */
    @Test
    void parserShouldRejectInvalidDsl() {
        assertThrows(ZeroException.class, () -> GmCommandDsl.parse(" "));
        assertThrows(ZeroException.class, () -> GmCommandDsl.parse("mail send player-1"));
        assertThrows(ZeroException.class, () -> GmCommandDsl.parse("/mail send \"player-1"));
    }
}
