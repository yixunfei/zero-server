package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.error.DataErrorCode;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 真实 Redis 原子脚本的保存契约及分桶批量查询；使用独立命名空间。 @author zn */
class RedisEnvelopeContractExternalIT {
    /** 本地镜像目录。 */
    @TempDir Path directory;
    /** 任意初始版本、同版重放、跳版本、long 精度、CAS 拒绝和镜像记录。 */
    @Test void snapshotAndCasContractsMatchOtherDrivers() {
        String namespace = "audit" + UUID.randomUUID().toString().replace("-", "");
        var keys = new DefaultRedisDataKeyStrategy(4);
        var journal = new LocalDiskDataJournal(directory);
        try (var client = new RedisDataAdapter(keys).createClient(RedisDriverSettings.fromSystemProperties())) {
            var store = new RedisDriverEnvelopeStore(namespace, "p", client, keys, journal);
            try {
                store.save(envelope(namespace, "p", 7));
                store.save(envelope(namespace, "p", 7));
                store.save(envelope(namespace, "p", 9007199254740993L));
                ZeroException stale = assertThrows(ZeroException.class, () -> store.save(envelope(namespace, "p", 9007199254740992L)));
                assertEquals(DataErrorCode.VERSION_CONFLICT, stale.errorCode());
                assertFalse(store.saveIfVersion(envelope(namespace, "p", 1), 0));
                assertEquals(3, journal.readAll(namespace, "p").size());
                for (int i = 0; i < 600; i++) store.save(envelope(namespace, "id" + i, 1));
                assertEquals(601, store.findAll().size());
                assertEquals(601, store.count());
                assertEquals(9007199254740993L, store.findById("p").orElseThrow().version());
            } finally {
                for (var value : store.findAll()) store.deleteById(value.id());
                for (int i = 0; i < 600; i++) { client.del(store.indexKey("id" + i)); client.del(store.journalKey("id" + i)); }
                client.del(store.indexKey("p")); client.del(store.journalKey("p"));
                client.del("zero:indexes:{" + namespace + ":p}");
            }
        }
    }

    private ZeroDataEnvelope envelope(final String namespace, final String id, final long version) {
        return new ZeroDataEnvelope(namespace, "p", id, version, 1, 1, 1, new byte[] {1});
    }
}
