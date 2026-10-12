package group.zn.zero.examples.multiplayer;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import group.zn.zero.data.mongo.MongoDriverSettings;
import group.zn.zero.data.redis.RedisDriverClientFactory;
import group.zn.zero.data.redis.RedisDriverSettings;
import group.zn.zero.discovery.ServiceInstance;
import group.zn.zero.discovery.nacos.NacosDiscoveryAdapter;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.bson.Document;

/**
 * 四组件连通与读写探针；普通命令行线程同步执行，不代表跨服业务或 Repository Adapter 验收。
 * 使用随机探针身份并清理本次记录；清理失败同样使执行失败，不覆盖他人数据。
 * @author zn
 */
public final class ExternalInfrastructureProbe {
    /** 单项远程等待预算，单位秒。 */
    private static final int TIMEOUT_SECONDS = 10;

    private ExternalInfrastructureProbe() { }

    /**
     * 依次验证组件，只有全部成功才输出总成功标记；每次使用独立连接与探针身份。
     * @param args 未使用参数；连接配置从环境变量读取。
     * @throws Exception 连接、读写校验、资源清理失败或线程被中断；异常保留原始原因。
     */
    public static void main(final String[] args) throws Exception {
        ExternalComponentSettings settings = ExternalComponentSettings.fromEnvironment();
        probeKafka(settings.kafkaBootstrap());
        probeMongo(settings.mongo());
        probeRedis(settings.redis());
        probeNacos(settings);
        System.out.println("MULTI_SERVER_PLAYER_EXTERNAL_OK");
    }

    private static void probeKafka(final String bootstrap) throws Exception {
        AdminClient client = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, TIMEOUT_SECONDS * 1000));
        try (AutoCloseable cleanup = () -> client.close(Duration.ofSeconds(5))) {
            if (client.describeCluster().nodes().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty()) {
                throw new IllegalStateException("Kafka metadata contains no broker");
            }
        }
        System.out.println("kafka=ok|check=metadata");
    }

    private static void probeMongo(final MongoDriverSettings settings) throws Exception {
        MongoClientSettings clientSettings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(settings.connectionString()))
                .applyToClusterSettings(builder -> builder.serverSelectionTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .applyToSocketSettings(builder -> builder.connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .build();
        try (var client = MongoClients.create(clientSettings)) {
            var collection = client.getDatabase(settings.databaseName()).getCollection("example_probe");
            String id = UUID.randomUUID().toString();
            // 先登记清理动作：即使写入响应丢失，也只清理本次随机 ID。
            try (AutoCloseable cleanup = () -> collection.deleteOne(Filters.eq("_id", id))) {
                collection.insertOne(new Document("_id", id).append("value", "ok"));
                Document read = collection.find(Filters.eq("_id", id)).first();
                if (read == null || !"ok".equals(read.getString("value"))) {
                    throw new IllegalStateException("MongoDB read-back mismatch");
                }
            }
        }
        System.out.println("mongo=ok|check=write-read-delete");
    }

    private static void probeRedis(final RedisDriverSettings settings) throws Exception {
        try (var client = RedisDriverClientFactory.create(settings, Duration.ofSeconds(TIMEOUT_SECONDS))) {
            String key = "zero:example:multi-server-player:" + UUID.randomUUID();
            try (AutoCloseable cleanup = () -> client.del(key)) {
                if (!"PONG".equalsIgnoreCase(client.ping())) {
                    throw new IllegalStateException("Redis ping failed");
                }
                client.setex(key, 60, "ok");
                if (!"ok".equals(client.get(key))) {
                    throw new IllegalStateException("Redis read-back mismatch");
                }
            }
        }
        System.out.println("redis=ok|check=write-read-delete");
    }

    private static void probeNacos(final ExternalComponentSettings settings) throws Exception {
        NacosDiscoveryAdapter adapter = new NacosDiscoveryAdapter(settings.nacos());
        // stop 负责注销本适配器注册的实例，并关闭 SDK；包含启动失败路径。
        try (AutoCloseable cleanup = adapter::stop) {
            adapter.start();
            String service = "zero-example-probe-" + UUID.randomUUID();
            ServiceInstance instance = new ServiceInstance(service, "probe", "127.0.0.1", 19090, true,
                    Map.of("example", "multi-server-player"));
            adapter.register(instance);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (adapter.lookup(service).stream().noneMatch(found -> found.instanceId().equals("probe"))) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Nacos registration not visible within probe budget");
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
        }
        System.out.println("nacos=ok|check=register-lookup-unregister");
    }
}
