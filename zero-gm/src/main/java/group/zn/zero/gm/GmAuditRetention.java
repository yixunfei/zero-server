package group.zn.zero.gm;

import java.time.Instant;
import java.util.Objects;

/** Retention and archive lifecycle boundary for sanitized audit records. */
public interface GmAuditRetention {
    RetentionDecision evaluate(GmAuditRecord record, Instant now);

    record RetentionDecision(Action action, String reason) {
        public RetentionDecision {
            Objects.requireNonNull(action, "action");
            if (reason == null || reason.isBlank() || reason.length() > 128) {
                throw new IllegalArgumentException("reason is invalid");
            }
        }
    }

    enum Action {
        KEEP,
        ARCHIVE,
        HOLD,
        EXPIRE
    }
}
