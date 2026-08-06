package group.zn.zero.data.postgresql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * JDBC 连接创建工厂。
 *
 * <p>生产实现委托 {@link java.sql.DriverManager}，包内测试通过该接缝验证传给驱动的原生超时。
 *
 * @author zn
 */
@FunctionalInterface
interface JdbcConnectionFactory {

    /**
     * 创建 JDBC 连接。
     *
     * @param jdbcUrl JDBC URL；不可为空。
     * @param properties 驱动属性；不可为空；可变、无序、非线程安全。
     * @return JDBC 连接；不可为空；调用方负责关闭。
     * @throws SQLException 当驱动无法建立连接时抛出。
     */
    Connection open(String jdbcUrl, Properties properties) throws SQLException;
}
