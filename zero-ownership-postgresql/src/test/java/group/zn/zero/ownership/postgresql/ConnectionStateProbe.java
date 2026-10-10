package group.zn.zero.ownership.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/** 在真实 JDBC 连接归还前检查 autoCommit，计数用于断言 create 未另借连接。 @author zn */
final class ConnectionStateProbe {
    /** 后端数据源。 */
    private final DataSource delegate;
    /** 本测试开启的连接数。 */
    private final AtomicInteger opened = new AtomicInteger();
    /** 初始连接状态；schema 初始化后可选择 false。 */
    private boolean initialAutoCommit = true;

    ConnectionStateProbe(final DataSource delegate) {
        this.delegate = delegate;
    }

    DataSource dataSource() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(delegate, args);
                        return result instanceof Connection connection ? track(connection) : result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    int opened() { return opened.get(); }

    void initialAutoCommit(final boolean value) { initialAutoCommit = value; }

    private Connection track(final Connection connection) throws java.sql.SQLException {
        opened.incrementAndGet();
        boolean expected = initialAutoCommit;
        connection.setAutoCommit(expected);
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        try {
                            assertEquals(expected, connection.getAutoCommit(), "autoCommit must be restored before close");
                        } finally {
                            connection.close();
                        }
                        return null;
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
