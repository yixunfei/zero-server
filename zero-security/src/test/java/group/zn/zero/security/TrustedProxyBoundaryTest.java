package group.zn.zero.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 从可信后缀确定来源，客户端前缀不得伪造白名单身份。 @author zn */
class TrustedProxyBoundaryTest {
    /** 客户端注入的左侧白名单地址必须被忽略。 */
    @Test void stopsAtFirstUntrustedHopFromRight() {
        var resolver = resolver();
        assertEquals("203.0.113.8", resolver.resolve("192.0.2.10",
                "198.51.100.1, 203.0.113.8, 192.0.2.11").sourceAddress());
        assertEquals("203.0.113.8", resolver.resolve("192.0.2.10", "garbage,203.0.113.8").sourceAddress());
    }

    /** 不可信直连忽略头，可信后缀非法或超预算则拒绝。 */
    @Test void validatesOnlyConsumedTrustedSuffixAndBudget() {
        assertEquals("203.0.113.8", resolver().resolve("203.0.113.8", "bad").sourceAddress());
        assertThrows(IllegalArgumentException.class, () -> resolver().resolve("192.0.2.10", "client.example"));
        assertThrows(IllegalArgumentException.class, () -> resolver().resolve("192.0.2.10", "1.2.3.4,"));
        assertThrows(IllegalArgumentException.class, () -> resolver().resolve("192.0.2.10", "1,2,3,4,5"));
    }

    private static TrustedProxyResolver resolver() {
        return new TrustedProxyResolver(List.of(ip -> ip.equals("192.0.2.10") || ip.equals("192.0.2.11")), 4);
    }
}
