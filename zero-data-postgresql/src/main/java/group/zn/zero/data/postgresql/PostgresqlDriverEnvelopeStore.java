package group.zn.zero.data.postgresql;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore;
import group.zn.zero.data.error.DataErrorCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * PostgreSQL JDBC-backed 通用对象表信封存储。
 *
 * @author zn
 */
public final class PostgresqlDriverEnvelopeStore implements ZeroDataEnvelopeStore {

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

    /**
     * 创建 PostgreSQL JDBC-backed 信封存储。
     *
     * @param namespace 数据命名空间；不可为空。
     * @param collection 数据集合名称；不可为空。
     * @param settings driver 配置；不可为空。
     * @throws NullPointerException 当必要参数为空时抛出。
     */
    public PostgresqlDriverEnvelopeStore(
            final String namespace,
            final String collection,
            final PostgresqlDriverSettings settings) {
        this(namespace, collection, Objects.requireNonNull(settings, "settings").tableName(), unpooled(settings));
    }

    /** Uses a caller-owned DataSource with auto-commit connections and a validated SQL table name. */
    public PostgresqlDriverEnvelopeStore(
            final String namespace, final String collection, final String tableName, final DataSource dataSource) {
        this.namespace = requireText(namespace, "namespace");
        this.collection = requireText(collection, "collection");
        this.tableName = PostgresqlDriverSettings.requireIdentifier(tableName);
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

    /**
     * 保存信封。
     *
     * @param envelope 信封；不可为空。
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
                + "updated_at_epoch_millis=excluded.updated_at_epoch_millis, payload=excluded.payload";
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
            statement.executeUpdate();
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
            throw ZeroException.of(DataErrorCode.WRITE_FAILED, "postgresql conditional write failed", ex);
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
        String sql = "select count(*) from " + tableName
                + " where namespace=? and collection=?";
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace);
            statement.setString(2, collection);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (SQLException ex) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "postgresql count failed", ex);
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
        String sql = "update " + tableName
                + " set version=?, schema_version=?, codec_version=?, "
                + "updated_at_epoch_millis=?, payload=? "
                + "where namespace=? and collection=? and id=? and version=?";
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, row.version());
            statement.setInt(2, row.schemaVersion());
            statement.setInt(3, row.codecVersion());
            statement.setLong(4, row.updatedAtEpochMillis());
            statement.setBytes(5, row.payload());
            statement.setString(6, row.namespace());
            statement.setString(7, row.collection());
            statement.setString(8, row.id());
            statement.setLong(9, expectedVersion);
            return statement.executeUpdate() == 1;
        }
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

    private static DataSource unpooled(final PostgresqlDriverSettings settings) {
        var source = new PGSimpleDataSource();
        source.setURL(settings.jdbcUrl());
        source.setUser(settings.username());
        source.setPassword(settings.password());
        return source;
    }

    private String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
