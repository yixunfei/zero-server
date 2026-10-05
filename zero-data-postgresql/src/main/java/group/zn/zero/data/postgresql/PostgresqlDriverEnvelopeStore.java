package group.zn.zero.data.postgresql;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.model.PageRequest;
import group.zn.zero.data.model.PageResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * PostgreSQL JDBC-backed 通用对象表信封存储。
 *
 * @author zn
 */
public final class PostgresqlDriverEnvelopeStore implements ZeroDataEnvelopeStore {

    /** 单次协议批次上限，限制参数和服务端执行成本。 */
    private static final int BATCH_SIZE = 256;

    /**
     * 数据命名空间。
     */
    private final String namespace;

    /**
     * 数据集合名称。
     */
    private final String collection;

    /**
     * Validated SQL table name.
     */
    private final String tableName;

    /** Caller-owned source; closing a borrowed connection returns it to its source. */
    private final DataSource dataSource;

    /** Uses a caller-owned DataSource with auto-commit connections and a validated SQL table name. */
    public PostgresqlDriverEnvelopeStore(
            final String namespace, final String collection, final String tableName, final DataSource dataSource) {
        this.namespace = requireText(namespace, "namespace");
        this.collection = requireText(collection, "collection");
        this.tableName = quoteIdentifier(PostgresqlDriverSettings.requireIdentifier(tableName));
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        initializeSchema();
    }

    /**
     * 根据编码 ID 查询信封。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @return 查询结果；不可为空；可能为空；线程安全。
     */
    @Override
    public Optional<ZeroDataEnvelope> findById(final String id) {
        String sql = "select namespace, collection, id, version, schema_version, codec_version, "
                + "updated_at_epoch_millis, payload from " + tableName
                + " where namespace=? and collection=? and id=?";
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, collection);
            statement.setString(3, Objects.requireNonNull(id, "id"));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(row(resultSet).toEnvelope());
            }
        } catch (SQLException ex) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "postgresql read failed", ex);
        }
    }

    /**
     * 查询全部信封。
     *
     * @return 信封列表；不可为空；可能为空；按 ID 有序；线程安全。
     */
    @Override
    public List<ZeroDataEnvelope> findAll() {
        String sql = "select namespace, collection, id, version, schema_version, codec_version, "
                + "updated_at_epoch_millis, payload from " + tableName
                + " where namespace=? and collection=? order by id";
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, collection);
            List<ZeroDataEnvelope> results = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    results.add(row(resultSet).toEnvelope());
                }
            }
            return List.copyOf(results);
        } catch (SQLException ex) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "postgresql read all failed", ex);
        }
    }

    /** 批量查询，复用一个连接；结果不可变，按输入 ID 排序，缺失项省略，重复项保留。 */
    @Override
    public List<ZeroDataEnvelope> findByIds(final Collection<String> ids) {
        List<String> current = List.copyOf(ids);
        if (current.isEmpty()) return List.of();
        var found = new HashMap<String, ZeroDataEnvelope>();
        String sql = "select * from " + tableName + " where namespace=? and collection=? and id=any(?)";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, collection);
            for (int offset = 0; offset < current.size(); offset += BATCH_SIZE) {
                var keys = connection.createArrayOf("text",
                        current.subList(offset, Math.min(offset + BATCH_SIZE, current.size())).toArray());
                try {
                    statement.setArray(3, keys);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            ZeroDataEnvelope envelope = row(rows).toEnvelope();
                            found.put(envelope.id(), envelope);
                        }
                    }
                } finally { keys.free(); }
            }
            List<ZeroDataEnvelope> result = new ArrayList<>(current.size());
            for (String id : current) {
                if (found.containsKey(id)) result.add(found.get(id));
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "postgresql batch read failed", failure);
        }
    }

    /** SQL 下推分页；按 ID 稳定排序，跨语句并发写时 items/total 为弱一致。 */
    @Override
    public PageResult<ZeroDataEnvelope> findPage(final PageRequest request) {
        Objects.requireNonNull(request, "request");
        String sql = "select * from " + tableName
                + " where namespace=? and collection=? order by id limit ? offset ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, collection);
            statement.setInt(3, request.limit());
            statement.setInt(4, request.offset());
            List<ZeroDataEnvelope> items = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) items.add(row(rows).toEnvelope());
            }
            long total = count(connection);
            long next = (long) request.offset() + items.size();
            return new PageResult<>(items, !items.isEmpty() && next < total ? String.valueOf(next) : null, total);
        } catch (SQLException failure) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "postgresql page read failed", failure);
        }
    }

    /** JDBC 有界批处理；逐项 CAS 独立提交，结果不可变并按输入顺序返回，非事务原子批次。 */
    @Override
    public List<Boolean> saveIfVersionBatch(final Collection<ConditionalWrite> writes) {
        List<ConditionalWrite> current = List.copyOf(writes);
        for (ConditionalWrite write : current) validateEnvelope(write.envelope());
        if (current.isEmpty()) return List.of();
        List<Boolean> result = new ArrayList<>(current.size());
        try (Connection connection = connection()) {
            int offset = 0;
            while (offset < current.size()) {
                boolean insert = current.get(offset).expectedVersion() == 0L;
                int end = offset + 1;
                while (end < current.size() && end - offset < BATCH_SIZE
                        && (current.get(end).expectedVersion() == 0L) == insert) end++;
                executeWrites(connection, current.subList(offset, end), insert, result);
                offset = end;
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw ZeroException.of(classifyWriteFailure(failure), "postgresql batch conditional write failed", failure);
        }
    }

    private void executeWrites(final Connection connection, final List<ConditionalWrite> writes,
                               final boolean insert, final List<Boolean> result) throws SQLException {
        // INSERT SELECT 防止 reWriteBatchedInserts 合并后只能返回 SUCCESS_NO_INFO，丢失逐项 CAS 结果。
        String sql = insert ? "insert into " + tableName
                + " (namespace, collection, id, version, schema_version, codec_version, updated_at_epoch_millis, payload)"
                + " select ?, ?, ?, ?, ?, ?, ?, ? on conflict (namespace, collection, id) do nothing"
                : updateSql();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (ConditionalWrite write : writes) {
                PostgresqlDataRow row = PostgresqlDataRow.fromEnvelope(write.envelope());
                if (insert) bindRow(statement, row);
                else bindUpdate(statement, row, write.expectedVersion());
                statement.addBatch();
            }
            int[] counts = statement.executeBatch();
            if (counts.length != writes.size()) throw new SQLException("invalid JDBC batch result size");
            for (int count : counts) {
                if (count != 0 && count != 1) throw new SQLException("JDBC batch requires exact CAS update counts");
                result.add(count == 1);
            }
        }
    }

    /** 批量删除，复用连接并按有界 ID 数组执行；不承诺批次事务原子性。 */
    @Override
    public void deleteAll(final Collection<String> ids) {
        List<String> current = List.copyOf(ids);
        if (current.isEmpty()) return;
        String sql = "delete from " + tableName + " where namespace=? and collection=? and id=any(?)";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, collection);
            for (int offset = 0; offset < current.size(); offset += BATCH_SIZE) {
                var keys = connection.createArrayOf("text",
                        current.subList(offset, Math.min(offset + BATCH_SIZE, current.size())).toArray());
                try { statement.setArray(3, keys); statement.executeUpdate(); }
                finally { keys.free(); }
            }
        } catch (SQLException failure) {
            throw ZeroException.of(DataErrorCode.DELETE_FAILED, "postgresql batch delete failed", failure);
        }
    }

    /**
     * 保存信封；不会覆盖后端更高版本。
     *
     * @param envelope 信封；不可为空。
     * @throws ZeroException 当后端已有更高版本或写入失败时抛出。
     */
    @Override
    public void save(final ZeroDataEnvelope envelope) {
        PostgresqlDataRow row = PostgresqlDataRow.fromEnvelope(validateEnvelope(envelope));
        String sql = "insert into " + tableName
                + " (namespace, collection, id, version, schema_version, codec_version, "
                + "updated_at_epoch_millis, payload) values (?, ?, ?, ?, ?, ?, ?, ?) "
                + "on conflict (namespace, collection, id) do update set "
                + "version=excluded.version, schema_version=excluded.schema_version, "
                + "codec_version=excluded.codec_version, "
                + "updated_at_epoch_millis=excluded.updated_at_epoch_millis, payload=excluded.payload "
                + "where " + tableName + ".version <= excluded.version";
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, row.namespace());
            statement.setString(2, row.collection());
            statement.setString(3, row.id());
            statement.setLong(4, row.version());
            statement.setInt(5, row.schemaVersion());
            statement.setInt(6, row.codecVersion());
            statement.setLong(7, row.updatedAtEpochMillis());
            statement.setBytes(8, row.payload());
            if (statement.executeUpdate() == 0) {
                throw ZeroException.of(DataErrorCode.VERSION_CONFLICT,
                        "postgresql save would move version backwards", null);
            }
        } catch (SQLException ex) {
            throw ZeroException.of(DataErrorCode.WRITE_FAILED, "postgresql write failed", ex);
        }
    }

    /**
     * 按期望版本条件保存信封。
     *
     * @param envelope 信封；不可为空。
     * @param expectedVersion 期望当前版本；必须大于等于 0。
     * @return true 表示保存成功；false 表示版本条件不满足；线程安全。
     */
    @Override
    public boolean saveIfVersion(final ZeroDataEnvelope envelope, final long expectedVersion) {
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        PostgresqlDataRow row = PostgresqlDataRow.fromEnvelope(validateEnvelope(envelope));
        try {
            if (expectedVersion == 0L) {
                return insertIfAbsent(row);
            }
            return updateIfVersion(row, expectedVersion);
        } catch (SQLException ex) {
            throw ZeroException.of(classifyWriteFailure(ex), "postgresql conditional write failed", ex);
        }
    }

    /**
     * 根据编码 ID 删除信封。
     *
     * @param id 编码后的存储 ID；不可为空。
     */
    @Override
    public void deleteById(final String id) {
        String sql = "delete from " + tableName
                + " where namespace=? and collection=? and id=?";
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, collection);
            statement.setString(3, Objects.requireNonNull(id, "id"));
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw ZeroException.of(DataErrorCode.DELETE_FAILED, "postgresql delete failed", ex);
        }
    }

    /**
     * 统计信封数量。
     *
     * @return 信封数量；线程安全。
     */
    @Override
    public long count() {
        try (Connection connection = connection()) {
            return count(connection);
        } catch (SQLException ex) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "postgresql count failed", ex);
        }
    }

    private long count(final Connection connection) throws SQLException {
        String sql = "select count(*) from " + tableName
                + " where namespace=? and collection=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, collection);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private void initializeSchema() {
        String sql = "create table if not exists " + tableName + " ("
                + "namespace varchar(128) not null, "
                + "collection varchar(128) not null, "
                + "id varchar(512) not null, "
                + "version bigint not null, "
                + "schema_version integer not null, "
                + "codec_version integer not null, "
                + "updated_at_epoch_millis bigint not null, "
                + "payload bytea not null, "
                + "primary key(namespace, collection, id))";
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException ex) {
            throw ZeroException.of(DataErrorCode.WRITE_FAILED, "initialize postgresql schema failed", ex);
        }
    }

    private boolean insertIfAbsent(final PostgresqlDataRow row) throws SQLException {
        String sql = "insert into " + tableName
                + " (namespace, collection, id, version, schema_version, codec_version, "
                + "updated_at_epoch_millis, payload) values (?, ?, ?, ?, ?, ?, ?, ?) "
                + "on conflict (namespace, collection, id) do nothing";
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bindRow(statement, row);
            return statement.executeUpdate() == 1;
        }
    }

    private boolean updateIfVersion(final PostgresqlDataRow row, final long expectedVersion) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(updateSql())) {
            bindUpdate(statement, row, expectedVersion);
            return statement.executeUpdate() == 1;
        }
    }

    private String updateSql() {
        return "update " + tableName
                + " set version=?, schema_version=?, codec_version=?, "
                + "updated_at_epoch_millis=?, payload=? "
                + "where namespace=? and collection=? and id=? and version=?";
    }

    private void bindUpdate(final PreparedStatement statement, final PostgresqlDataRow row,
                            final long expectedVersion) throws SQLException {
            statement.setLong(1, row.version());
            statement.setInt(2, row.schemaVersion());
            statement.setInt(3, row.codecVersion());
            statement.setLong(4, row.updatedAtEpochMillis());
            statement.setBytes(5, row.payload());
            statement.setString(6, row.namespace());
            statement.setString(7, row.collection());
            statement.setString(8, row.id());
            statement.setLong(9, expectedVersion);
    }

    private void bindRow(final PreparedStatement statement, final PostgresqlDataRow row) throws SQLException {
        statement.setString(1, row.namespace());
        statement.setString(2, row.collection());
        statement.setString(3, row.id());
        statement.setLong(4, row.version());
        statement.setInt(5, row.schemaVersion());
        statement.setInt(6, row.codecVersion());
        statement.setLong(7, row.updatedAtEpochMillis());
        statement.setBytes(8, row.payload());
    }

    private PostgresqlDataRow row(final ResultSet resultSet) throws SQLException {
        return new PostgresqlDataRow(
                resultSet.getString("namespace"),
                resultSet.getString("collection"),
                resultSet.getString("id"),
                resultSet.getLong("version"),
                resultSet.getInt("schema_version"),
                resultSet.getInt("codec_version"),
                resultSet.getLong("updated_at_epoch_millis"),
                resultSet.getBytes("payload"));
    }

    private ZeroDataEnvelope validateEnvelope(final ZeroDataEnvelope envelope) {
        ZeroDataEnvelope current = Objects.requireNonNull(envelope, "envelope");
        if (!namespace.equals(current.namespace()) || !collection.equals(current.collection())) {
            throw ZeroException.of(DataErrorCode.MAPPING_INVALID,
                    "postgresql data envelope collection mismatch", null);
        }
        return current;
    }

    private Connection connection() throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            if (!connection.getAutoCommit()) {
                throw new SQLException("repository DataSource must provide auto-commit connections");
            }
            return connection;
        } catch (SQLException failure) {
            try { connection.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }

    private DataErrorCode classifyWriteFailure(final SQLException failure) {
        String state = failure.getSQLState();
        if (state != null && (state.startsWith("08") || state.startsWith("53"))) {
            return DataErrorCode.BACKEND_UNAVAILABLE;
        }
        if ("40001".equals(state) || "40P01".equals(state)) {
            return DataErrorCode.CONCURRENT_WRITE;
        }
        if ("23505".equals(state)) {
            return DataErrorCode.VERSION_CONFLICT;
        }
        return DataErrorCode.WRITE_FAILED;
    }

    private String quoteIdentifier(final String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
