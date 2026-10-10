package group.zn.zero.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/** Unicode 装饰不能把凭据和个人标识降级为可明文输出字段。 @author zn */
class UnicodeSensitiveFieldTest {
    /** 默认规则同时识别全角、零宽字符、复合路径和常见 PII。 */
    @Test void canonicalAliasesRemainRestricted() {
        var policy = DefaultSensitiveFieldPolicy.instance();
        for (String field : java.util.List.of("password\u200B", "ｐａｓｓｗｏｒｄ", "access.\uFEFFtoken", "se\u00ADcret")) {
            assertEquals(SensitiveFieldAction.REJECT, policy.actionFor(field, "test"), field);
        }
        for (String field : java.util.List.of("userId", "session.id", "email", "phone", "ｐｌａｙｅｒＩｄ")) {
            assertEquals(SensitiveFieldAction.REDACT, policy.actionFor(field, "test"), field);
        }
        assertEquals(SensitiveFieldAction.ALLOW, policy.actionFor("operation", "move"));
    }
}
