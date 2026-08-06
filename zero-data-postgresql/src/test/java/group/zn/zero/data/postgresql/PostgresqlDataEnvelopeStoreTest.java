package group.zn.zero.data.postgresql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.data.envelope.ZeroDataEnvelope;
import org.junit.jupiter.api.Test;

/**
 * PostgreSQL 通用对象表信封存储测试。
 *
 * @author zn
 */
class PostgresqlDataEnvelopeStoreTest {

    /**
     * 验证 PostgreSQL row 形态可无损承载统一信封。
     */
    @Test
    void storeShouldMapEnvelopeToRow() {
        PostgresqlDataEnvelopeStore store = new PostgresqlDataEnvelopeStore();
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
        PostgresqlDataRow row = store.row("str:cDE").orElseThrow();
        ZeroDataEnvelope restored = store.findById("str:cDE").orElseThrow();

        assertEquals("str:cDE", row.id());
        assertEquals("player", row.collection());
        assertEquals(1L, store.count());
        assertEquals(envelope.version(), restored.version());
        assertArrayEquals(envelope.payload(), restored.payload());
    }

    /**
     * 验证 PostgreSQL row store 条件写会拒绝旧版本覆盖。
     */
    @Test
    void storeShouldRejectStaleConditionalWrite() {
        PostgresqlDataEnvelopeStore store = new PostgresqlDataEnvelopeStore();
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
