package group.zn.zero.net.kcp.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.net.kcp.KcpSessionSnapshot;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import redis.clients.jedis.RedisClient;

/** Redis Lua owner/freeze/migrate/claim 状态机回归；需要本机 KCP Redis 环境时启用。 @author zn */
@Timeout(15)
class RedisKcpSessionStoreTest {
    @Test void completesAtomicMigrationWhenRedisIsAvailable() throws Exception {
        RedisClient client;
        try { client = RedisClient.create("redis://127.0.0.1:6388"); client.ping(); }
        catch (RuntimeException unavailable) { Assumptions.assumeTrue(false, "KCP Redis environment unavailable"); return; }
        ExecutorService io = Executors.newFixedThreadPool(2);
        try (client; io) {
            var store = new RedisKcpSessionStore(client, "zkcp-test-" + System.nanoTime(), io, 32);
            assertTrue(store.acquire(91, "source", 1, Duration.ofSeconds(10)).toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(store.freeze(91, "source", 1).toCompletableFuture().get(2, TimeUnit.SECONDS));
            var snapshot = new KcpSessionSnapshot(91, 1, "source", "player-91", new InetSocketAddress("127.0.0.1", 3301),
                    Instant.now().plusSeconds(30), new byte[8], 4, 3);
            assertTrue(store.migrate(snapshot, "target", 2, Duration.ofSeconds(10)).toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals("target", store.owner(91).toCompletableFuture().get(2, TimeUnit.SECONDS).owner());
            assertNotNull(store.load(91).toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(store.claim(91, "target", 2, Duration.ofSeconds(10)).toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertFalse(store.renew(91, "source", 1, Duration.ofSeconds(10)).toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertTrue(store.release(91, "target", 2).toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(null, store.owner(91).toCompletableFuture().get(2, TimeUnit.SECONDS));
        }
    }
}
