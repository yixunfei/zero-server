package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryGmIdempotencyStoreTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    @Test
    void claimIsAtomicAndSameFingerprintIsInProgress() {
        InMemoryGmIdempotencyStore store = new InMemoryGmIdempotencyStore();
        assertEquals(GmIdempotencyStore.State.CLAIMED,
                store.claim("key", "fingerprint", NOW, TTL).state());
        assertEquals(GmIdempotencyStore.State.IN_PROGRESS,
                store.claim("key", "fingerprint", NOW.plusSeconds(1), TTL).state());
        assertEquals(1, store.size());
    }

    @Test
    void differentFingerprintIsConflict() {
        InMemoryGmIdempotencyStore store = new InMemoryGmIdempotencyStore();
        store.claim("key", "one", NOW, TTL);
        assertEquals(GmIdempotencyStore.State.CONFLICT,
                store.claim("key", "two", NOW, TTL).state());
    }

    @Test
    void completedResponseIsReplayedUntilTtl() {
        InMemoryGmIdempotencyStore store = new InMemoryGmIdempotencyStore();
        store.claim("key", "fingerprint", NOW, TTL);
        GmOperationResponse response = new GmOperationResponse("ZERO-OK", "success", null, "trace");
        store.complete("key", "fingerprint", response, NOW.plus(TTL));
        assertEquals(GmIdempotencyStore.State.COMPLETED,
                store.claim("key", "fingerprint", NOW.plusSeconds(1), TTL).state());
        assertEquals(response,
                store.claim("key", "fingerprint", NOW.plusSeconds(1), TTL).response());
        assertEquals(GmIdempotencyStore.State.CLAIMED,
                store.claim("key", "fingerprint", NOW.plusSeconds(31), TTL).state());
    }

    @Test
    void updateRequiresMatchingClaim() {
        InMemoryGmIdempotencyStore store = new InMemoryGmIdempotencyStore();
        assertThrows(IllegalStateException.class,
                () -> store.complete("missing", "fingerprint",
                        new GmOperationResponse("ZERO-OK", "success", null, "trace"), NOW.plus(TTL)));
    }
}
