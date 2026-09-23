package group.zn.zero.data.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PostgresqlDataSourceTest {
    @Test
    void borrowedConnectionClosesAfterSchemaCreationAndQueryFailure() {
        var opened = new AtomicInteger();
        var closed = new AtomicInteger();
        DataSource source = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, arguments) -> {
                    if (!method.getName().equals("getConnection")) { throw new AssertionError(method); }
                    opened.incrementAndGet();
                    return connection(closed, true);
                });
        var store = new PostgresqlDriverEnvelopeStore("tenant", "balances", "records", source);
        assertEquals(1, opened.get());
        assertEquals(1, closed.get());
        assertThrows(ZeroException.class, () -> store.findById("id"));
        assertEquals(2, opened.get());
        assertEquals(2, closed.get());
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresqlDriverEnvelopeStore("tenant", "balances", "unsafe;drop", source));
        assertEquals(2, opened.get(), "invalid table must fail before borrowing a connection");
    }

    @Test
    void manualTransactionConnectionsAreRejectedAndReturnedWithoutCommitting() {
        var closed = new AtomicInteger();
        DataSource source = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, arguments) -> {
                    if (!method.getName().equals("getConnection")) { throw new AssertionError(method); }
                    return connection(closed, false);
                });
        assertThrows(ZeroException.class, () -> new PostgresqlDriverEnvelopeStore("tenant", "balances", "records", source));
        assertEquals(1, closed.get());
    }

    private static Connection connection(final AtomicInteger closed, final boolean autoCommit) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getAutoCommit" -> autoCommit;
                    case "createStatement" -> Proxy.newProxyInstance(Statement.class.getClassLoader(),
                            new Class<?>[] {Statement.class}, (statement, operation, parameters) -> {
                                if (operation.getName().equals("executeUpdate")) { return 0; }
                                if (operation.getName().equals("close")) { return null; }
                                throw new AssertionError(operation);
                            });
                    case "prepareStatement" -> throw new SQLException("synthetic query failure");
                    case "close" -> { closed.incrementAndGet(); yield null; }
                    default -> throw new AssertionError(method);
                });
    }
}
