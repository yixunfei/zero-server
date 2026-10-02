package group.zn.zero.ownership.postgresql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;

/** PostgreSQL ownership 适配器共享的连接和 schema 边界。 */
public final class PostgresqlOwnershipDatabase {
    private final DataSource dataSource;
    private final String ownershipTable;
    private final String stateTable;
    private final String requestTable;
    private final String migrationTable;
    private final String routeTable;

    /** 使用调用方管理的 DataSource 创建适配器数据库。 */
    public PostgresqlOwnershipDatabase(final DataSource dataSource, final String tablePrefix) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        String prefix = validateIdentifier(tablePrefix);
        ownershipTable = quote(prefix + "_ownership_lease");
        stateTable = quote(prefix + "_ownership_state");
        requestTable = quote(prefix + "_ownership_request");
        migrationTable = quote(prefix + "_ownership_migration");
        routeTable = quote(prefix + "_ownership_route");
        initializeSchema();
    }

    /** 返回一条未开启事务的连接；调用方负责关闭。 */
    Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /** 返回 ownership lease 表名。 */
    String ownershipTable() { return ownershipTable; }

    /** 返回状态表名。 */
    String stateTable() { return stateTable; }

    /** 返回请求去重表名。 */
    String requestTable() { return requestTable; }

    /** 返回迁移记录表名。 */
    String migrationTable() { return migrationTable; }

    /** 返回 owner 路由表名。 */
    String routeTable() { return routeTable; }

    private void initializeSchema() {
        String ownership = "create table if not exists " + ownershipTable + " ("
                + "resource_type varchar(128) not null, resource_id varchar(512) not null, "
                + "generation bigint not null, process_id varchar(256) not null, "
                + "incarnation_id varchar(256) not null, epoch bigint not null, "
                + "expires_at timestamptz not null, primary key(resource_type, resource_id, generation))";
        String state = "create table if not exists " + stateTable + " ("
                + "resource_type varchar(128) not null, resource_id varchar(512) not null, "
                + "generation bigint not null, version bigint not null, payload bytea, "
                + "primary key(resource_type, resource_id, generation))";
        String request = "create table if not exists " + requestTable + " ("
                + "resource_type varchar(128) not null, resource_id varchar(512) not null, "
                + "generation bigint not null, request_id varchar(512) not null, "
                + "expected_version bigint not null, request_payload bytea, new_version bigint not null, "
                + "result_payload bytea, primary key(resource_type, resource_id, generation, request_id))";
        String migration = "create table if not exists " + migrationTable + " ("
                + "migration_id varchar(512) primary key, resource_type varchar(128) not null, "
                + "resource_id varchar(512) not null, generation bigint not null, "
                + "source_process_id varchar(256) not null, source_incarnation_id varchar(256) not null, "
                + "target_process_id varchar(256) not null, target_incarnation_id varchar(256) not null, "
                + "state varchar(32) not null, checkpoint_version bigint not null, updated_at timestamptz not null)";
        String route = "create table if not exists " + routeTable + " ("
                + "resource_type varchar(128) not null, resource_id varchar(512) not null, generation bigint not null, "
                + "process_id varchar(256) not null, incarnation_id varchar(256) not null, epoch bigint not null, "
                + "endpoint varchar(2048) not null, expires_at timestamptz not null, "
                + "primary key(resource_type, resource_id, generation))";
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(ownership);
            statement.executeUpdate(state);
            statement.executeUpdate(request);
            statement.executeUpdate(migration);
            statement.executeUpdate(route);
            statement.executeUpdate("alter table " + migrationTable
                    + " add column if not exists checkpoint_schema_version varchar(128) not null default '1'");
            statement.executeUpdate("alter table " + migrationTable
                    + " add column if not exists checkpoint_codec_version varchar(128) not null default '1'");
            statement.executeUpdate("alter table " + migrationTable
                    + " add column if not exists history_complete boolean not null default true");
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot initialize PostgreSQL ownership schema", failure);
        }
    }

    private static String validateIdentifier(final String value) {
        String identifier = Objects.requireNonNull(value, "tablePrefix");
        if (!identifier.matches("[A-Za-z][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("tablePrefix must be a SQL identifier");
        }
        return identifier;
    }

    private static String quote(final String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
