package group.zn.zero.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** HMAC 跨传输验证及权限集合编码歧义回归。 @author zn */
class MetadataCanonicalSecurityTest {
    @Test void permissionBoundariesCannotShareASignature() {
        var first = snapshot(Set.of("read", "write"), "");
        assertThrows(IllegalArgumentException.class, () -> snapshot(Set.of("read\u001fwrite"), ""));
        var ambiguous = snapshot(Set.of("readwrite"), "");
        assertFalse(Arrays.equals(first.canonicalBytes(), ambiguous.canonicalBytes()));
        var signer = SecurityMetadataAssertion.digest(java.util.Arrays.copyOf(new byte[]{1, 2, 3}, 32));
        String signature = signer.sign(first);
        assertTrue(signer.verify(snapshot(first.permissions(), signature)));
        assertFalse(signer.verify(snapshot(ambiguous.permissions(), signature)));
        assertFalse(SecurityMetadataAssertion.digest(java.util.Arrays.copyOf(new byte[]{4, 5, 6}, 32)).verify(snapshot(first.permissions(), signature)));
        assertTrue(signer.verify(SecurityMetadataHttpCodec.decode(
                SecurityMetadataHttpCodec.encode(snapshot(first.permissions(), signature)))));
    }

    /** 创建固定时刻的安全元数据。 */
    private SecurityMetadataSnapshot snapshot(final Set<String> permissions, final String signature) {
        return new SecurityMetadataSnapshot("user", "tcp", "peer", "source", "trace", "corr", permissions,
                "assertion", Instant.ofEpochMilli(1), Instant.ofEpochMilli(60_001), signature);
    }
}
