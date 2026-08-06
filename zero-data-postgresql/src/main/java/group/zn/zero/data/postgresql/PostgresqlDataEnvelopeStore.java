package group.zn.zero.data.postgresql;

import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL 通用对象表形态的信封存储。
 *
 * <p>当前实现使用本地内存 map 保持单元测试可运行；真实 JDBC 接入时应保持该 row 字段语义不变。
 *
 * @author zn
 */
public final class PostgresqlDataEnvelopeStore implements ZeroDataEnvelopeStore {

    /**
     * 数据行存储。
     */
    private final Map<String, PostgresqlDataRow> rows = new LinkedHashMap<>();

    /**
     * 创建 PostgreSQL 通用对象表形态的信封存储。
     */
    public PostgresqlDataEnvelopeStore() {
    }

    /**
     * 根据编码 ID 查询信封。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @return 查询结果；不可为空；可能为空；线程安全。
     */
    @Override
    public synchronized Optional<ZeroDataEnvelope> findById(final String id) {
        return row(id).map(PostgresqlDataRow::toEnvelope);
    }

    /**
     * 查询全部信封。
     *
     * @return 信封列表；不可为空；可能为空；有序；线程安全。
     */
    @Override
    public synchronized List<ZeroDataEnvelope> findAll() {
        return rows.values().stream()
                .map(PostgresqlDataRow::toEnvelope)
                .toList();
    }

    /**
     * 保存信封。
     *
     * @param envelope 信封；不可为空。
     */
    @Override
    public synchronized void save(final ZeroDataEnvelope envelope) {
        PostgresqlDataRow row = PostgresqlDataRow.fromEnvelope(envelope);
        rows.put(row.id(), row);
    }

    /**
     * 按期望版本条件保存信封。
     *
     * @param envelope 信封；不可为空。
     * @param expectedVersion 期望当前版本；必须大于等于 0。
     * @return true 表示保存成功；false 表示版本条件不满足；线程安全。
     */
    @Override
    public synchronized boolean saveIfVersion(final ZeroDataEnvelope envelope, final long expectedVersion) {
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        PostgresqlDataRow row = PostgresqlDataRow.fromEnvelope(envelope);
        PostgresqlDataRow previous = rows.get(row.id());
        if (expectedVersion == 0L) {
            if (previous != null) {
                return false;
            }
        } else if (previous == null || previous.version() != expectedVersion) {
            return false;
        }
        rows.put(row.id(), row);
        return true;
    }

    /**
     * 根据编码 ID 删除信封。
     *
     * @param id 编码后的存储 ID；不可为空。
     */
    @Override
    public synchronized void deleteById(final String id) {
        rows.remove(Objects.requireNonNull(id, "id"));
    }

    /**
     * 统计信封数量。
     *
     * @return 信封数量；线程安全。
     */
    @Override
    public synchronized long count() {
        return rows.size();
    }

    /**
     * 根据编码 ID 查询底层数据行。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @return 数据行；为空表示不存在；线程安全。
     */
    public synchronized Optional<PostgresqlDataRow> row(final String id) {
        return Optional.ofNullable(rows.get(Objects.requireNonNull(id, "id")));
    }
}
