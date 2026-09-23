package group.zn.zero.security;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Application-supplied verifier for received security metadata. */
@FunctionalInterface
public interface SecurityMetadataVerifier {
    /** Revalidates a received snapshot at the transport boundary. */
    CompletionStage<SecurityContext> verify(SecurityMetadataSnapshot snapshot, Instant receivedAt);

    /** Fail-closed verifier for production defaults. */
    static SecurityMetadataVerifier failClosed() {
        return (snapshot, receivedAt) -> java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /** Creates a verifier from a synchronous function while retaining an async contract. */
    static SecurityMetadataVerifier synchronous(final java.util.function.Function<SecurityMetadataSnapshot, SecurityContext> verifier) {
        Objects.requireNonNull(verifier, "verifier");
        return (snapshot, receivedAt) -> java.util.concurrent.CompletableFuture.completedFuture(
                verifier.apply(Objects.requireNonNull(snapshot, "snapshot")));
    }
}
