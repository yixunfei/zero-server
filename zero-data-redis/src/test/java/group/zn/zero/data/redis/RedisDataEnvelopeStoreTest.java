package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeCodec;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Redis snapshot/index/journal 信封存储测试。
 *
 * @author zn
 */
class RedisDataEnvelopeStoreTest {

    /**
     * 临时目录。
     */
    @TempDir
    private Path tempDir;

    /**
     * 验证 Redis snapshot、bucket index、journal 和本地 zlog 共享统一信封。
     */
    @Test
    void storeShouldWriteSnapshotIndexJournalAndLocalDisk() {
        DefaultRedisDataKeyStrategy keyStrategy = new DefaultRedisDataKeyStrategy(16);
        LocalDiskDataJournal localJournal = new LocalDiskDataJournal(tempDir);
        RedisDataEnvelopeStore store = new RedisDataEnvelopeStore(
                "game",
                "player",
                keyStrategy,
                localJournal,
                new ZeroDataEnvelopeCodec());
        ZeroDataEnvelope envelope = new ZeroDataEnvelope(
                "game",
                "player",
                "str:cDE",
                1L,
                1,
                1,
                1000L,
                new byte[] {1, 2});

        store.save(envelope);
        RedisDataSnapshot snapshot = store.snapshot("str:cDE").orElseThrow();
        List<RedisDataJournalEntry> journalEntries = store.journalEntries(snapshot.journalKey());
        List<RedisDataJournalEntry> diskEntries = localJournal.readAll("game", "player");

        assertEquals(keyStrategy.dataKey("game", "player", "str:cDE"), snapshot.dataKey());
        assertTrue(store.indexIds(snapshot.indexKey()).contains("str:cDE"));
        assertEquals(RedisDataJournalOperation.PUT, journalEntries.getFirst().operation());
        assertEquals(1, diskEntries.size());
        assertArrayEquals(envelope.payload(), store.findById("str:cDE").orElseThrow().payload());

        store.deleteById("str:cDE");

        List<RedisDataJournalEntry> afterDelete = store.journalEntries(snapshot.journalKey());
        assertFalse(store.findById("str:cDE").isPresent());
        assertEquals(RedisDataJournalOperation.DELETE, afterDelete.getLast().operation());
        assertEquals(2, localJournal.readAll("game", "player").size());
    }

    /**
     * 验证 Redis snapshot store 条件写会拒绝旧版本覆盖。
     */
    @Test
    void storeShouldRejectStaleConditionalWrite() {
        RedisDataEnvelopeStore store = new RedisDataEnvelopeStore("game", "player");
        ZeroDataEnvelope created = envelope(1L, new byte[] {1});
        ZeroDataEnvelope stale = envelope(2L, new byte[] {2});
        ZeroDataEnvelope updated = envelope(2L, new byte[] {3});

        assertTrue(store.saveIfVersion(created, 0L));
        assertFalse(store.saveIfVersion(stale, 0L));
        assertTrue(store.saveIfVersion(updated, 1L));
        assertFalse(store.saveIfVersion(stale, 1L));

        assertArrayEquals(new byte[] {3}, store.findById("str:cDE").orElseThrow().payload());
    }

    private ZeroDataEnvelope envelope(final long version, final byte[] payload) {
        return new ZeroDataEnvelope("game", "player", "str:cDE", version, 1, 1, 1000L, payload);
    }
}
