package group.zn.zero.starter.production;

import com.mongodb.client.MongoClient;
import group.zn.zero.data.mongo.MongoDataAdapter;
import group.zn.zero.data.mongo.MongoDataHealthCheck;
import group.zn.zero.data.mongo.MongoDriverSettings;
import group.zn.zero.data.postgresql.PostgresqlDataHealthCheck;
import group.zn.zero.data.postgresql.PostgresqlDriverSettings;
import group.zn.zero.data.redis.RedisCacheHealthCheck;
import group.zn.zero.data.redis.RedisDataHealthCheck;
import group.zn.zero.data.redis.RedisDriverClientFactory;
import group.zn.zero.data.redis.RedisDriverSettings;
import java.time.Duration;
import redis.clients.jedis.RedisClient;

/**
 * Production 数据 Adapter 的一次性 startup health probes。
 *
 * <p>每个 probe 使用当前共享预算创建临时驱动 client，使驱动原生 timeout 不会被更早阶段已消耗的预算
 * 放宽。临时 client 始终在当前调用内关闭，不创建框架外线程池，也不承诺驱动忽略原生 timeout 时的硬取消。
 *
 * @author zn
 */
final class ProductionAdapterHealthProbes {

    private ProductionAdapterHealthProbes() {
    }

    /**
     * 探测 MongoDB。
     *
     * @param settings MongoDB driver 配置；不可为空。
     * @param timeout 当前剩余预算；必须为正。
     */
    static void mongo(final MongoDriverSettings settings, final Duration timeout) {
        MongoDataAdapter adapter = new MongoDataAdapter();
        try (MongoClient client = adapter.createClient(settings, timeout)) {
            new MongoDataHealthCheck(client, settings.databaseName()).checkOrThrow();
        }
    }

    /**
     * 探测 Redis data。
     *
     * @param settings Redis driver 配置；不可为空。
     * @param timeout 当前剩余预算；必须为正。
     */
    static void redisData(final RedisDriverSettings settings, final Duration timeout) {
        try (RedisClient client = RedisDriverClientFactory.create(settings, timeout)) {
            new RedisDataHealthCheck(client).checkOrThrow();
        }
    }

    /**
     * 探测 Redis cache。
     *
     * @param settings Redis driver 配置；不可为空。
     * @param timeout 当前剩余预算；必须为正。
     */
    static void redisCache(final RedisDriverSettings settings, final Duration timeout) {
        try (RedisClient client = RedisDriverClientFactory.create(settings, timeout)) {
            new RedisCacheHealthCheck(client).checkOrThrow();
        }
    }

    /**
     * 探测 PostgreSQL。
     *
     * <p>PostgreSQL JDBC 原生 timeout 只有整秒精度；剩余预算不足一秒时在进入驱动前报告预算耗尽。
     *
     * @param settings PostgreSQL driver 配置；不可为空。
     * @param timeout 当前剩余预算；必须至少为一秒。
     * @throws ProductionAdapterException 剩余预算不足一秒时抛出安全预算异常。
     */
    static void postgresql(final PostgresqlDriverSettings settings, final Duration timeout) {
        if (timeout.compareTo(Duration.ofSeconds(1)) < 0) {
            throw ProductionAdapterFailures.failure(
                    ZeroProductionRuntimeBuilder.ADAPTER_POSTGRESQL_DATA,
                    ProductionAdapterFailurePhase.STARTUP_BUDGET,
                    ProductionAdapterErrorCode.STARTUP_BUDGET_EXHAUSTED,
                    ProductionAdapterErrorCode.STARTUP_BUDGET_EXHAUSTED.message());
        }
        new PostgresqlDataHealthCheck(settings, timeout).checkOrThrow();
    }
}
