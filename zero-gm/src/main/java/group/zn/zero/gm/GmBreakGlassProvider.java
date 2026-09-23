package group.zn.zero.gm;

import java.time.Instant;

/** Explicit break-glass verification and one-shot consumption boundary. */
public interface GmBreakGlassProvider {
    Verification verify(GmBreakGlassGrant grant, String scope, String operationFingerprint, Instant now);

    record Verification(boolean accepted, String code) {
        public Verification {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("code is invalid");
            }
        }
    }
}
