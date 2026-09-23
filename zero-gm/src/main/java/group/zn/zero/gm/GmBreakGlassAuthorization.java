package group.zn.zero.gm;

import java.time.Instant;
import java.util.Objects;

/** Scoped break-glass authorization port. */
@FunctionalInterface
public interface GmBreakGlassAuthorization {
    Decision authorize(GmBreakGlassRequest request);

    record GmBreakGlassRequest(
            GmCommandContext context,
            String scope,
            String operationFingerprint,
            Instant requestedAt) {
        public GmBreakGlassRequest {
            Objects.requireNonNull(context, "context");
            text(scope, "scope");
            text(operationFingerprint, "operationFingerprint");
            Objects.requireNonNull(requestedAt, "requestedAt");
        }

        private static void text(final String value, final String name) {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(name + " is invalid");
            }
        }
    }

    record Decision(boolean allowed, String code) {
        public Decision {
            if (code == null || code.isBlank() || code.length() > 128) {
                throw new IllegalArgumentException("code is invalid");
            }
        }
    }
}
