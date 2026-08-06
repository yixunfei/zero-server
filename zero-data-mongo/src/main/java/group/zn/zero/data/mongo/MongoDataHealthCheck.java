package group.zn.zero.data.mongo;

import com.mongodb.client.MongoClient;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import java.util.Objects;
import org.bson.Document;

/**
 * MongoDB 数据适配器健康检查。
 *
 * @author zn
 */
public final class MongoDataHealthCheck {

    /**
     * MongoDB client。
     */
    private final MongoClient client;

    /**
     * 数据库名称。
     */
    private final String databaseName;

    /**
     * 创建 MongoDB 数据适配器健康检查。
     *
     * @param client MongoDB client；不可为空。
     * @param databaseName 数据库名称；不可为空。
     * @throws NullPointerException 当必要参数为空时抛出。
     */
    public MongoDataHealthCheck(final MongoClient client, final String databaseName) {
        this.client = Objects.requireNonNull(client, "client");
        this.databaseName = Objects.requireNonNull(databaseName, "databaseName");
    }

    /**
     * 执行健康检查。
     *
     * @return true 表示 MongoDB 可用；线程安全性由 MongoDB driver 保证。
     */
    public boolean check() {
        try {
            checkOrThrow();
            return true;
        } catch (ZeroException ex) {
            return false;
        }
    }

    /**
     * 执行健康检查，失败时抛出统一异常。
     *
     * @throws ZeroException MongoDB 不可用时抛出，绑定 `DataErrorCode.BACKEND_UNAVAILABLE`。
     */
    public void checkOrThrow() {
        try {
            client.getDatabase(databaseName).runCommand(new Document("ping", 1));
        } catch (RuntimeException ex) {
            throw ZeroException.of(DataErrorCode.BACKEND_UNAVAILABLE, "mongo backend is unavailable", ex);
        }
    }
}
