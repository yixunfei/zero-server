package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证唯一请求键的容量与 TTL 索引，防止常驻进程累积所有历史请求。 */
class GmIdempotencyCapacityTest {
    @Test
    void boundsDistinctClaimsAndKeepsExistingClaimsWhenFull() {
        InMemoryGmIdempotencyStore store = new InMemoryGmIdempotencyStore();
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        for (int index = 0; index < 4096; index++) {
            assertEquals(GmIdempotencyStore.State.CLAIMED,
                    store.claim("key-" + index, "operation", now, Duration.ofMinutes(1)).state());
        }
        assertEquals(GmIdempotencyStore.State.CAPACITY_REJECTED,
                store.claim("overflow", "operation", now, Duration.ofMinutes(1)).state());
        assertEquals(GmIdempotencyStore.State.IN_PROGRESS,
                store.claim("key-0", "operation", now, Duration.ofMinutes(1)).state());
        assertEquals(4096, store.size());
        assertEquals(GmIdempotencyStore.State.CAPACITY_REJECTED,
                store.claim("next", "operation", now.plusSeconds(60), Duration.ofMinutes(1)).state());
        for (int index = 0; index < 4096; index++) store.fail("key-" + index, "operation", now.plusSeconds(60));
        assertEquals(GmIdempotencyStore.State.CLAIMED,
                store.claim("next", "operation", now.plusSeconds(60), Duration.ofMinutes(1)).state());
        assertEquals(1, store.size());
    }

    @Test
    void completionMovesExpiryWithoutRetainingTheOldDeadline() {
        InMemoryGmIdempotencyStore store = new InMemoryGmIdempotencyStore();
        Instant now = Instant.parse("2026-10-03T00:00:00Z");
        store.claim("key", "operation", now, Duration.ofSeconds(1));
        store.fail("key", "operation", now.plusSeconds(10));
        store.claim("other", "operation", now.plusSeconds(2), Duration.ofSeconds(20));
        assertEquals(GmIdempotencyStore.State.FAILED,
                store.claim("key", "operation", now.plusSeconds(2), Duration.ofSeconds(1)).state());
        store.claim("last", "operation", now.plusSeconds(10), Duration.ofSeconds(1));
        assertEquals(2, store.size());
    }
}
