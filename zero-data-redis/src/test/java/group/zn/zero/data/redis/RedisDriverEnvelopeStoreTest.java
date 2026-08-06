package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.data.envelope.ZeroDataEnvelope;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.RedisClient;

/**
 * Redis driver-backed 信封存储测试。
 *
 * @author zn
 */
class RedisDriverEnvelopeStoreTest {

    /**
     * 临时目录。
     */
    @TempDir
    private Path tempDir;

    /**
     * 验证 Redis 不可用时，本地 zlog 可以承担降级写入。
     */
    @Test
    void storeShouldAppendLocalJournalWhenRedisUnavailable() {
        LocalDiskDataJournal localJournal = new LocalDiskDataJournal(tempDir);
        try (RedisClient client = RedisClient.create("redis://127.0.0.1:1/0")) {
            RedisDriverEnvelopeStore store = new RedisDriverEnvelopeStore(
                    "game",
                    "player",
                    client,
                    new DefaultRedisDataKeyStrategy(16),
                    localJournal);
            ZeroDataEnvelope envelope = new ZeroDataEnvelope(
                    "game",
                    "player",
                    "str:cDE",
                    1L,
                    1,
                    1,
                    1000L,
                    new byte[] {1, 2});

            boolean saved = store.saveIfVersion(envelope, 0L);

            assertTrue(saved);
            assertEquals(1, localJournal.readAll("game", "player").size());
        }
    }
}
