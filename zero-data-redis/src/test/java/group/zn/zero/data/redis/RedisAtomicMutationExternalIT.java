package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.error.DataErrorCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisClient;

/** 真实 Redis 的错误类型、版本冲突与多 key 原子更新回归。 @author zn */
class RedisAtomicMutationExternalIT {
    /** 每个测试使用独立集合，避免污染其他用例。 */
    private final String collection = "atomic-" + UUID.randomUUID();
    /** 删除前检查索引和 journal 类型，脚本错误不能留下已删除快照。 */
    @Test
    void deleteTypeErrorsLeaveSnapshotAndVersionUntouched() {
        try (RedisClient client = new RedisDataAdapter().createClient(RedisDriverSettings.fromSystemProperties())) {
            for (boolean corruptIndex : List.of(true, false)) {
                var store = store(client);
                var envelope = envelope();
                try {
                    assertTrue(store.saveIfVersion(envelope, 0));
                    String wrongKey = corruptIndex ? store.indexKey("id") : store.journalKey("id");
                    client.del(wrongKey);
                    client.set(wrongKey, "wrong-type");
                    ZeroException failure = assertThrows(ZeroException.class, () -> store.deleteById("id"));
                    assertEquals(DataErrorCode.DELETE_FAILED, failure.errorCode());
                    assertTrue(store.findById("id").isPresent());
                    assertEquals("1", client.get(store.versionKey("id")));
                } finally {
                    cleanup(client, store);
                }
            }
        }
    }

    /** 保存失败不能留下快照/版本，正确删除同步更新快照、索引及日志。 */
    @Test
    void saveTypeErrorsHaveNoPartialMutationAndDeleteIsConsistent() {
        try (RedisClient client = new RedisDataAdapter().createClient(RedisDriverSettings.fromSystemProperties())) {
            var store = store(client);
            try {
                for (String key : List.of(store.indexKey("id"), collectionIndexKey(), store.journalKey("id"))) {
                    client.set(key, "wrong-type");
                    assertThrows(ZeroException.class, () -> store.saveIfVersion(envelope(), 0));
                    assertFalse(client.exists(store.dataKey("id")));
                    assertFalse(client.exists(store.versionKey("id")));
                    client.del(key);
                }
                assertTrue(store.saveIfVersion(envelope(), 0));
                assertEquals(1, store.count());
                store.deleteById("id");
                assertEquals(0, store.count());
                assertEquals(store.count(), store.findAll().size());
                assertEquals(2, client.llen(store.journalKey("id")));
            } finally {
                cleanup(client, store);
            }
        }
    }

    /** 读取快照后版本检查不一致必须报告冲突，不能静默宣告删除成功。 */
    @Test
    void deleteVersionMismatchIsVisibleAndPreservesData() {
        try (RedisClient client = new RedisDataAdapter().createClient(RedisDriverSettings.fromSystemProperties())) {
            var store = store(client);
            try {
                assertTrue(store.saveIfVersion(envelope(), 0));
                client.set(store.versionKey("id"), "2");
                ZeroException failure = assertThrows(ZeroException.class, () -> store.deleteById("id"));
                assertEquals(DataErrorCode.VERSION_CONFLICT, failure.errorCode());
                assertTrue(store.findById("id").isPresent());
                assertEquals(1, store.count());
                assertEquals(1, client.llen(store.journalKey("id")));
            } finally {
                cleanup(client, store);
            }
        }
    }

    private RedisDriverEnvelopeStore store(final RedisClient client) {
        return new RedisDriverEnvelopeStore("hardening", collection, client,
                new DefaultRedisDataKeyStrategy(16), null);
    }

    private ZeroDataEnvelope envelope() {
        return new ZeroDataEnvelope("hardening", collection, "id", 1, 1, 1, 1000, new byte[] {1});
    }

    private String collectionIndexKey() {
        return "zero:indexes:{hardening:" + collection + "}";
    }

    private void cleanup(final RedisClient client, final RedisDriverEnvelopeStore store) {
        client.del(store.dataKey("id"), store.versionKey("id"), store.indexKey("id"),
                store.journalKey("id"), collectionIndexKey());
    }
}
