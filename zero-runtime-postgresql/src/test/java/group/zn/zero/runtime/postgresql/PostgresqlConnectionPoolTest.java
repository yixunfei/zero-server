package group.zn.zero.runtime.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.data.envelope.EnvelopeRepositoryFactory;
import group.zn.zero.data.postgresql.PostgresqlDriverSettings;
import group.zn.zero.runtime.production.ProductionConfigResolver;
import group.zn.zero.runtime.production.ProductionStartupBudget;
import group.zn.zero.runtime.spi.ComponentCreationContext;
import group.zn.zero.runtime.spi.ResourceRegistrar;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PostgresqlConnectionPoolTest {
    private static final PostgresqlDriverSettings SETTINGS = new PostgresqlDriverSettings(
            "jdbc:postgresql://127.0.0.1:1/test", "test", "test", "records");

    @Test
    void poolReusesConnectionsBoundsAcquisitionAndClosesPhysicalResources() throws Exception {
        var opened = new AtomicInteger();
        var closed = new AtomicInteger();
        var pool = PostgresqlConnectionPool.create(SETTINGS, 1, Duration.ofSeconds(1));
        try (pool) {
            assertNull(pool.getHikariPoolMXBean(), "configuration must not open physical connections");
            pool.setDataSource(source(opened, closed));
            try (var first = pool.getConnection()) {
                assertFalse(first.isClosed());
                assertThrows(SQLException.class, pool::getConnection);
                assertEquals(1, opened.get());
                assertEquals(0, closed.get());
            }
            try (var reused = pool.getConnection()) {
                assertFalse(reused.isClosed());
                assertEquals(1, opened.get());
            }
        }
        assertTrue(pool.isClosed());
        assertEquals(1, closed.get());
        assertThrows(SQLException.class, pool::getConnection);
    }

    @Test
    void providerRegistersPoolBeforeRepositoryFactoryWithoutConnecting() throws Exception {
        var resources = new ArrayList<AutoCloseable>();
        ResourceRegistrar registrar = new ResourceRegistrar() {
            @Override
            public <T extends AutoCloseable> T register(final T resource) { resources.add(resource); return resource; }
        };
        var context = (ComponentCreationContext) Proxy.newProxyInstance(ComponentCreationContext.class.getClassLoader(),
                new Class<?>[] {ComponentCreationContext.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("resources")) { return registrar; }
                    throw new AssertionError(method);
                });
        var config = new MapZeroConfig(Map.of("zero.adapter.data.postgresql.enabled", "true",
                PostgresqlDriverSettings.JDBC_URL_PROPERTY, SETTINGS.jdbcUrl(),
                PostgresqlDriverSettings.USERNAME_PROPERTY, SETTINGS.username(),
                PostgresqlDriverSettings.PASSWORD_PROPERTY, SETTINGS.password(),
                PostgresqlDriverSettings.TABLE_NAME_PROPERTY, SETTINGS.tableName()));
        var resolver = new ProductionConfigResolver(config, key -> null, key -> null);
        var budget = new ProductionStartupBudget(Duration.ofSeconds(10), Duration.ofSeconds(2));
        var resolution = ProductionPostgresqlDataProvider.resolve(resolver, budget, 3);
        try {
            resolution.provider().create(context);
            assertEquals(2, resources.size());
            var pool = assertInstanceOf(HikariDataSource.class, resources.getFirst());
            assertEquals(3, pool.getMaximumPoolSize());
            assertNull(pool.getHikariPoolMXBean());
            assertInstanceOf(EnvelopeRepositoryFactory.class, resources.getLast());
        } finally {
            for (var resource : resources.reversed()) { resource.close(); }
        }
        assertTrue(((HikariDataSource) resources.getFirst()).isClosed());
        assertThrows(IllegalArgumentException.class, () -> PostgresqlRuntime.module(0));
    }

    private static DataSource source(final AtomicInteger opened, final AtomicInteger closed) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("toString")) { return "test-source"; }
                    if (method.getName().equals("setLoginTimeout")) { return null; }
                    if (method.getName().equals("getLoginTimeout")) { return 1; }
                    if (!method.getName().equals("getConnection")) { throw new AssertionError(method); }
                    opened.incrementAndGet();
                    var isClosed = new AtomicBoolean();
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (connection, operation, parameters) -> switch (operation.getName()) {
                                case "isValid", "getAutoCommit" -> true;
                                case "isReadOnly" -> false;
                                case "isClosed" -> isClosed.get();
                                case "getNetworkTimeout" -> 0;
                                case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                                case "setNetworkTimeout", "clearWarnings" -> null;
                                case "close" -> {
                                    if (isClosed.compareAndSet(false, true)) { closed.incrementAndGet(); }
                                    yield null;
                                }
                                case "toString" -> "test-connection";
                                default -> throw new AssertionError(operation);
                            });
                });
    }
}
