package group.zn.zero.gm;

import java.time.Duration;
import java.util.Objects;

/** Explicit retention policy for audit records. */
public record GmAuditRetentionPolicy(Duration hotRetention, Duration archiveAfter, boolean legalHoldSupported) {
    public GmAuditRetentionPolicy {
        positive(hotRetention, "hotRetention");
        positive(archiveAfter, "archiveAfter");
        if (archiveAfter.compareTo(hotRetention) < 0) {
            throw new IllegalArgumentException("archiveAfter must not precede hotRetention");
        }
    }

    private static void positive(final Duration value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
