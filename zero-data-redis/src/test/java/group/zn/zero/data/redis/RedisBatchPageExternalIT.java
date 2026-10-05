package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore.ConditionalWrite;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.model.PageRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.RedisClient;

/** Redis 有界 pipeline、MGET 和页 payload 下推的真实服务验证，无全库清理。 @author zn */
class RedisBatchPageExternalIT {

    /** 每次测试独立的本地 journal。 */
    @TempDir
    private Path tempDir;

    /** 跨多个 slot 桶与 256 分块；仅读取当前页信封，CAS 逐项结果精确。 */
    @Test
    void batchPageAndPartialConflictPreserveContracts() {
        String namespace = namespace();
        var journal = new LocalDiskDataJournal(tempDir);
        try (var client = new RedisDataAdapter().createClient(RedisDriverSettings.fromSystemProperties())) {
            var store = store(client, namespace, journal);
            List<String> ids = IntStream.range(0, 300).mapToObj(i -> String.format("p%03d", i)).toList();
            var cleanupIds = new ArrayList<>(ids);
            cleanupIds.add("p301");
            try {
                var writes = ids.stream().map(id -> new ConditionalWrite(envelope(namespace, id, 1), 0)).toList();
                assertTrue(store.saveIfVersionBatch(writes).stream().allMatch(Boolean.TRUE::equals));
                assertEquals(300, journal.readAll(namespace, "batch").size());
                var queries = new ArrayList<>(ids.reversed());
                queries.addAll(List.of("absent", "p000"));
                var expected = new ArrayList<>(ids.reversed());
                expected.add("p000");
                assertEquals(expected, store.findByIds(queries).stream().map(ZeroDataEnvelope::id).toList());

                client.set(store.dataKey("p299").getBytes(StandardCharsets.UTF_8), new byte[] {0});
                var page = store.findPage(new PageRequest(100, 7));
                assertEquals(ids.subList(100, 107), page.items().stream().map(ZeroDataEnvelope::id).toList());
                assertEquals("107", page.nextCursor());
                assertEquals(300, page.total());
                assertThrows(ZeroException.class, () -> store.findById("p299"));
                store.save(envelope(namespace, "p299", 1));
                assertTrue(store.findPage(new PageRequest(Integer.MAX_VALUE, 7)).items().isEmpty());

                var mixed = List.of(new ConditionalWrite(envelope(namespace, "p000", 2), 1),
                        new ConditionalWrite(envelope(namespace, "p001", 2), 9),
                        new ConditionalWrite(envelope(namespace, "p301", 1), 0),
                        new ConditionalWrite(envelope(namespace, "p000", 3), 2));
                assertEquals(List.of(true, false, true, true), store.saveIfVersionBatch(mixed));
                assertEquals(3, store.findById("p000").orElseThrow().version());
                assertEquals(1, store.findById("p001").orElseThrow().version());
                var deletes = new ArrayList<>(cleanupIds);
                deletes.addAll(List.of("p000", "absent"));
                store.deleteAll(deletes);
                assertEquals(0, store.count());
            } finally {
                cleanup(client, store, namespace, cleanupIds);
            }
        }
    }

    /** 中间项 WRONGTYPE 不妨碍检查后续响应及其本地持久化镜像，错误仍向上传播。 */
    @Test
    void pipelineErrorStillJournalsEverySuccessfulItem() {
        String namespace = namespace();
        var journal = new LocalDiskDataJournal(tempDir);
        try (var client = new RedisDataAdapter().createClient(RedisDriverSettings.fromSystemProperties())) {
            var store = store(client, namespace, journal);
            List<String> ids = List.of("before", "bad", "after");
            try {
                client.lpush(store.versionKey("bad"), "wrong-type");
                var writes = ids.stream().map(id -> new ConditionalWrite(envelope(namespace, id, 1), 0)).toList();
                assertEquals(DataErrorCode.WRITE_FAILED,
                        assertThrows(ZeroException.class, () -> store.saveIfVersionBatch(writes)).errorCode());
                assertTrue(store.findById("before").isPresent());
                assertTrue(store.findById("bad").isEmpty());
                assertTrue(store.findById("after").isPresent());
                assertEquals(List.of("before", "after"),
                        journal.readAll(namespace, "batch").stream().map(RedisDataJournalEntry::id).toList());
            } finally {
                cleanup(client, store, namespace, ids);
            }
        }
    }

    /** 创建拥有独立逻辑集合与本地镜像的测试 store。 */
    private RedisDriverEnvelopeStore store(final RedisClient client, final String namespace,
            final LocalDiskDataJournal journal) {
        return new RedisDriverEnvelopeStore(namespace, "batch", client, new DefaultRedisDataKeyStrategy(16), journal);
    }

    /** 只删除本次生成命名空间的 key，保留共享数据库所有其他数据。 */
    private void cleanup(final RedisClient client, final RedisDriverEnvelopeStore store,
            final String namespace, final List<String> ids) {
        var keys = new HashSet<String>();
        for (String id : ids) {
            keys.addAll(List.of(store.dataKey(id), store.versionKey(id), store.indexKey(id), store.journalKey(id)));
        }
        keys.add("zero:indexes:{" + namespace + ":batch}");
        client.del(keys.toArray(String[]::new));
    }

    /** 独立命名空间，不使用共享固定名称。 */
    private String namespace() {
        return "perf" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 创建只含固定测试 payload 的信封。 */
    private ZeroDataEnvelope envelope(final String namespace, final String id, final long version) {
        return new ZeroDataEnvelope(namespace, "batch", id, version, 1, 1, 1, new byte[] {1});
    }
}
