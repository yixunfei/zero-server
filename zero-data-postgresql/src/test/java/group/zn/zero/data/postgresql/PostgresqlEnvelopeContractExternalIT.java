package group.zn.zero.data.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore;
import group.zn.zero.data.error.DataErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 真实后端与内存实现的快照/CAS 契约，使用独立命名空间。 @author zn */
class PostgresqlEnvelopeContractExternalIT {
    /** 外部依赖不可用时失败，避免将跳过误认为契约通过。 */
    @Test void realDriverContract() {
        String namespace = "audit" + UUID.randomUUID().toString().replace("-", "");
        var store = new PostgresqlDriverEnvelopeStore(namespace, "contract", PostgresqlDriverSettings.fromSystemProperties());
        try { verify(store, namespace); }
        finally { store.deleteById("p"); }
    }

    /** 同一断言同时校验内存模拟，防止测试环境接受生产不允许的版本回退。 */
    @Test void memoryContract() { verify(new PostgresqlDataEnvelopeStore(), "audit"); }

    private void verify(final ZeroDataEnvelopeStore store, final String namespace) {
        store.save(envelope(namespace, 7));
        store.save(envelope(namespace, 7));
        assertEquals(7, store.findById("p").orElseThrow().version());
        store.save(envelope(namespace, 9007199254740993L));
        ZeroException failure = assertThrows(ZeroException.class,
                () -> store.save(envelope(namespace, 9007199254740992L)));
        assertEquals(DataErrorCode.VERSION_CONFLICT, failure.errorCode());
        assertFalse(store.saveIfVersion(envelope(namespace, 1), 0));
        assertFalse(store.saveIfVersion(envelope(namespace, 8), 7));
        assertTrue(store.saveIfVersion(envelope(namespace, 9007199254740994L), 9007199254740993L));
        assertEquals(9007199254740994L, store.findById("p").orElseThrow().version());
        assertEquals(1, store.count());
        store.deleteById("p");
        assertTrue(store.saveIfVersion(envelope(namespace, 1), 0));
        assertEquals(1, store.findAll().size());
        store.deleteById("p");
    }

    private ZeroDataEnvelope envelope(final String namespace, final long version) {
        return new ZeroDataEnvelope(namespace, "contract", "p", version, 1, 1, 1, new byte[] {1});
    }
}
