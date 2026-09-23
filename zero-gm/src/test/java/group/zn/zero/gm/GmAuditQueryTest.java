package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GmAuditQueryTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void queryRequiresBoundedWindowAndLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> new GmAuditQuery(NOW, NOW.plusSeconds(1), null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new GmAuditQuery(NOW, NOW.plusSeconds(1), null, null, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new GmAuditQuery(NOW.plusSeconds(5), NOW, null, null, 10));
    }

    @Test
    void retentionPolicyRejectsInconsistentWindows() {
        assertThrows(IllegalArgumentException.class,
                () -> new GmAuditRetentionPolicy(Duration.ofDays(30), Duration.ofDays(7), true));
        GmAuditRetentionPolicy policy = new GmAuditRetentionPolicy(
                Duration.ofDays(7), Duration.ofDays(30), true);
        assertTrue(policy.legalHoldSupported());
    }

    @Test
    void securityFailureRejectsUnboundedOrControlInput() {
        assertThrows(IllegalArgumentException.class,
                () -> new GmSecurityFailure("code", "phase", "trace", "corr",
                        -1, "fingerprint", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new GmSecurityFailure("code\u0000", "phase", "trace", "corr",
                        10, "fingerprint", NOW));
        GmSecurityFailure failure = new GmSecurityFailure(
                "ZERO-GM-REQUEST-INVALID", "parse", "trace", "corr", 128, "fp-1", NOW);
        assertEquals("ZERO-GM-REQUEST-INVALID", failure.code());
        assertFalse(failure.toString().contains("fingerprint"));
    }

    @Test
    void queryPageRequiresCursorConsistency() {
        assertThrows(IllegalArgumentException.class,
                () -> new GmAuditPage(List.of(), null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new GmAuditPage(List.of(), "cursor", false));
        assertTrue(new GmAuditPage(List.of(), null, false).records().isEmpty());
    }
}
