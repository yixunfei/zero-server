package group.zn.zero.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** 审计日志行分隔与双向文本控制字符不能改变显示结构。 @author zn */
class LogUnicodeControlTest {
    @Test void preservesChineseButEscapesUnicodeLayoutControls() {
        String input = "正常\u2028\u2029\u202A\u202E\u2066\u2069";
        assertTrue(LogTextEscaper.containsControl(input));
        assertEquals("正常\\u2028\\u2029\\u202A\\u202E\\u2066\\u2069", LogTextEscaper.escapeControls(input));
        assertEquals("正常", LogTextEscaper.escapeControls("正常"));
    }
}
