package group.zn.zero.security;

import java.time.Instant;

/** TLS material source boundary; concrete providers own keystores and secrets. */
@FunctionalInterface
public interface TlsMaterialProvider {
    TlsMaterialSnapshot current();

    record TlsMaterialSnapshot(String version, Instant loadedAt, Instant expiresAt, String materialReference) {
        public TlsMaterialSnapshot {
            SecurityValues.require(version, "version", 128);
            java.util.Objects.requireNonNull(loadedAt, "loadedAt");
            java.util.Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(loadedAt)) throw new IllegalArgumentException("expiresAt must be after loadedAt");
            SecurityValues.require(materialReference, "materialReference", 256);
            if (materialReference.toLowerCase(java.util.Locale.ROOT).matches(".*(password|secret|private.?key|token).*")) {
                throw new IllegalArgumentException("materialReference must not contain secret material");
            }
        }
        public boolean expired(Instant now) { return !expiresAt.isAfter(java.util.Objects.requireNonNull(now, "now")); }
    }
}
