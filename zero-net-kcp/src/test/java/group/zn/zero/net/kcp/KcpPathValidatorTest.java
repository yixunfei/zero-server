package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

/** 路径验证的端点绑定、候选限流、放大上限和旧路径排空回归。 @author zn */
class KcpPathValidatorTest {
    /** 伪造 token 或跨地址复用不改变路由；成功后其他候选失效。 */
    @Test void bindsChallengesToCandidateAddressAndExpiresOldRoute() {
        var options = KcpPathOptions.validated();
        var paths = new KcpPathValidator(options);
        var old = address("127.0.0.1", 1001);
        var next = address("127.0.0.2", 1002);
        var other = address("::1", 1003);
        long now = 1_000_000_000L;
        paths.bind(old);
        byte[] token = paths.challenge(next, now, 88, 88);
        assertNotNull(token);
        byte[] forged = token.clone(); forged[0] ^= 1;
        assertFalse(paths.confirm(next, forged, now));
        assertFalse(paths.confirm(other, token, now));
        assertEquals(old, paths.active());
        assertFalse(paths.accepts(next, now));
        byte[] otherToken = paths.challenge(other, now + options.retryInterval().toNanos(), 88, 88);
        assertNotNull(otherToken);
        assertTrue(paths.confirm(next, token, now + options.retryInterval().toNanos()));
        assertEquals(next, paths.active());
        assertFalse(paths.confirm(other, otherToken, now + options.retryInterval().toNanos()));
        assertTrue(paths.accepts(old, now + options.retryInterval().toNanos()));
        assertNull(paths.challenge(old, now + options.retryInterval().toNanos(), 88, 88));
        assertFalse(paths.accepts(old, now + options.retryInterval().toNanos() + options.retiredPathLifetime().toNanos()));
    }
    /** 拒绝放大，限制候选数和新建速率；超时不会因重试延长。 */
    @Test void boundsAmplificationCandidatesAndTimeout() {
        var options = KcpPathOptions.validated();
        var paths = new KcpPathValidator(options);
        var first = address("127.0.0.1", 1101);
        var second = address("127.0.0.1", 1102);
        var third = address("127.0.0.1", 1103);
        long now = 1_000_000_000L; long retry = options.retryInterval().toNanos();
        assertNull(paths.challenge(first, now, 87, 88));
        byte[] token = paths.challenge(first, now, 88, 88);
        assertNotNull(token);
        assertNull(paths.challenge(first, now + 1, 88, 88));
        assertNull(paths.challenge(second, now + 1, 88, 88));
        assertNotNull(paths.challenge(second, now + retry, 88, 88));
        assertNull(paths.challenge(third, now + 2 * retry, 88, 88));
        assertFalse(paths.confirm(first, token, now + options.timeout().toNanos()));
        assertNotNull(paths.challenge(third, now + options.timeout().toNanos(), 88, 88));
    }
    /** 未启用的路径策略不生成挑战，旧固定地址语义保持显式。 */
    @Test void fixedPolicyNeverCreatesCandidate() {
        var paths = new KcpPathValidator(KcpPathOptions.fixed());
        var old = address("127.0.0.1", 1001);
        paths.bind(old);
        assertNull(paths.challenge(address("::1", 1001), 1_000_000_000L, 88, 88));
        assertEquals(old, paths.active());
    }
    private static InetSocketAddress address(final String host, final int port) { return new InetSocketAddress(host, port); }
}
