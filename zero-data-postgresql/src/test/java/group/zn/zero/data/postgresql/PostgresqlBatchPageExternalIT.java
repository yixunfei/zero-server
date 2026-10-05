package group.zn.zero.data.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore.ConditionalWrite;
import group.zn.zero.data.model.PageRequest;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** PostgreSQL 真实批次、精确 CAS、分页下推及物理连接复用验证。 @author zn */
class PostgresqlBatchPageExternalIT {

    /** 跨越 256 分块并启用 JDBC rewrite，冲突不能被报告为成功；只清理本测试表。 */
    @Test
    void batchPageAndPoolPreserveContracts() throws Exception {
        var base = PostgresqlDriverSettings.fromSystemProperties();
        String table = "perf_" + UUID.randomUUID().toString().replace("-", "");
        String url = base.jdbcUrl() + (base.jdbcUrl().contains("?") ? "&" : "?") + "reWriteBatchedInserts=true";
        var settings = new PostgresqlDriverSettings(url, base.username(), base.password(), table);
        var pool = PostgresqlPool.create(settings, 1, Duration.ofSeconds(3));
        try (pool) {
            var store = new PostgresqlDriverEnvelopeStore("performance", "batch", table, pool);
            try {
                assertEquals(backendPid(pool), backendPid(pool));
                List<String> ids = IntStream.range(0, 300).mapToObj(i -> String.format("p%03d", i)).toList();
                List<ConditionalWrite> writes = ids.stream().map(id -> new ConditionalWrite(envelope(id, 1), 0)).toList();
                assertTrue(store.saveIfVersionBatch(writes).stream().allMatch(Boolean.TRUE::equals));
                var queries = new ArrayList<>(ids.reversed());
                queries.add("absent");
                queries.add("p000");
                var found = store.findByIds(queries);
                var expected = new ArrayList<>(ids.reversed());
                expected.add("p000");
                assertEquals(expected, found.stream().map(ZeroDataEnvelope::id).toList());
                assertThrows(UnsupportedOperationException.class, () -> found.clear());

                execute(pool, "update " + table + " set schema_version=0 where id='p299'");
                var page = store.findPage(new PageRequest(100, 7));
                assertEquals(ids.subList(100, 107), page.items().stream().map(ZeroDataEnvelope::id).toList());
                assertEquals("107", page.nextCursor());
                assertEquals(300, page.total());
                assertThrows(RuntimeException.class, () -> store.findById("p299"));
                execute(pool, "update " + table + " set schema_version=1 where id='p299'");
                assertTrue(store.findPage(new PageRequest(Integer.MAX_VALUE, 7)).items().isEmpty());

                var mixed = List.of(new ConditionalWrite(envelope("p000", 2), 1),
                        new ConditionalWrite(envelope("p001", 2), 9),
                        new ConditionalWrite(envelope("p301", 1), 0),
                        new ConditionalWrite(envelope("p000", 3), 2),
                        new ConditionalWrite(envelope("p000", 1), 0));
                assertEquals(List.of(true, false, true, true, false), store.saveIfVersionBatch(mixed));
                assertEquals(3, store.findById("p000").orElseThrow().version());
                assertEquals(1, store.findById("p001").orElseThrow().version());
                var deletes = new ArrayList<>(ids);
                deletes.addAll(List.of("p301", "p000", "absent"));
                store.deleteAll(deletes);
                assertEquals(0, store.count());
            } finally {
                execute(pool, "drop table if exists " + table);
            }
        }
        assertTrue(pool.isClosed());
    }

    /** 返回借用连接对应物理会话；连接关闭后归还池。 */
    private int backendPid(final javax.sql.DataSource source) throws Exception {
        try (Connection connection = source.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("select pg_backend_pid()")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** SQL 仅使用测试自身生成的安全表名，不接受外部文本。 */
    private void execute(final javax.sql.DataSource source, final String sql) throws Exception {
        try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    /** 创建测试信封；payload 不含外部数据。 */
    private ZeroDataEnvelope envelope(final String id, final long version) {
        return new ZeroDataEnvelope("performance", "batch", id, version, 1, 1, 1, new byte[] {1});
    }
}
