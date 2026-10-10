package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 双栈源地址的等价表示与 CIDR 边界。 @author zn */
class GmSourceIpPolicyTest {
    @Test void mappedIpv6MatchesIpv4AndMappedNetworks() {
        var ipv4 = GmSourceIpPolicy.of(List.of("192.0.2.0/24"));
        assertTrue(ipv4.allows("::ffff:192.0.2.10"));
        var mapped = GmSourceIpPolicy.of(List.of("::ffff:192.0.2.0/120"));
        assertTrue(mapped.allows("192.0.2.255"));
        assertTrue(mapped.allows("::ffff:c000:020a"));
        assertFalse(mapped.allows("192.0.3.1"));
        assertThrows(IllegalArgumentException.class, () -> GmSourceIpPolicy.of(List.of("::ffff:192.0.2.0/95")));
        var nativeV6 = GmSourceIpPolicy.of(List.of("2001:db8::/32"));
        assertTrue(nativeV6.allows("2001:db8::1"));
        assertFalse(nativeV6.allows("::ffff:192.0.2.10"));
        assertFalse(nativeV6.allows("localhost"));
    }
}
