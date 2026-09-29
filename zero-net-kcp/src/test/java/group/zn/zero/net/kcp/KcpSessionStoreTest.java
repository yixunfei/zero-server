package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 内存双节点迁移屏障和代际拒绝回归。 @author zn */
class KcpSessionStoreTest {
    @Test void freezesMigratesClaimsAndRejectsOldGeneration() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(final java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var store = new InMemoryKcpSessionStore(clock, 8);
        var source = new KcpSessionCoordinator(new KcpSessionServices("source", store, Duration.ofSeconds(10), Duration.ofSeconds(1)));
        var target = new KcpSessionCoordinator(new KcpSessionServices("target", store, Duration.ofSeconds(10), Duration.ofSeconds(1)));
        assertTrue(source.acquire(7, 1).toCompletableFuture().join());
        assertTrue(source.freeze(7, 1).toCompletableFuture().join());
        var snapshot = new KcpSessionSnapshot(7, 1, "source", "player-7",
                new InetSocketAddress("127.0.0.1", 3456), now.get().plusSeconds(30), new byte[8], 4, 3);
        assertTrue(source.migrate(snapshot, "target", 2).toCompletableFuture().join());
        assertNotNull(target.load(7).toCompletableFuture().join());
        assertTrue(target.claim(7, 2).toCompletableFuture().join());
        assertFalse(source.renew(7, 1).toCompletableFuture().join());
        assertEquals("target", target.owner(7).toCompletableFuture().join().owner());
        assertFalse(source.acquire(7, 1).toCompletableFuture().join());
    }
}
