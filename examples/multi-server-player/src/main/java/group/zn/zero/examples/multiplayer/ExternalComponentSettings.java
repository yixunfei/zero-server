package group.zn.zero.examples.multiplayer;

import group.zn.zero.data.mongo.MongoDriverSettings;
import group.zn.zero.data.redis.RedisDriverSettings;
import group.zn.zero.discovery.nacos.NacosDiscoverySettings;
import group.zn.zero.rpc.kafka.KafkaRpcSettings;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 从示例环境变量显式构造连接配置；不读取 JVM 数据库属性、不创建连接。
 * @param kafkaBootstrap 非空 Kafka bootstrap。
 * @param nacosAddress 非空 Nacos HTTP 地址，客户端 gRPC 端口为 HTTP 端口加 1000。
 * @param mongo 非空 Mongo 配置。
 * @param redis 非空 Redis 配置。
 * @author zn
 */
public record ExternalComponentSettings(String kafkaBootstrap, String nacosAddress,
        MongoDriverSettings mongo, RedisDriverSettings redis) {
    /** 校验配置；空依赖抛 NullPointerException，空白地址抛 IllegalArgumentException。 */
    public ExternalComponentSettings {
        Objects.requireNonNull(kafkaBootstrap, "kafkaBootstrap");
        Objects.requireNonNull(nacosAddress, "nacosAddress");
        Objects.requireNonNull(mongo, "mongo");
        Objects.requireNonNull(redis, "redis");
        if (kafkaBootstrap.isBlank() || nacosAddress.isBlank()) {
            throw new IllegalArgumentException("component addresses must not be blank");
        }
    }

    /** @return 不可空、不可变配置；读取进程环境，默认与示例 Compose 隔离端口一致，无外部变更。 */
    public static ExternalComponentSettings fromEnvironment() {
        return new ExternalComponentSettings(
                setting("ZERO_KAFKA_BOOTSTRAP", "127.0.0.1:19092"),
                setting("ZERO_NACOS_ADDRESS", "127.0.0.1:18848"),
                new MongoDriverSettings(setting("ZERO_MONGO_URI", "mongodb://127.0.0.1:27018"),
                        setting("ZERO_MONGO_DATABASE", "zero_multi_server_player")),
                new RedisDriverSettings(setting("ZERO_REDIS_URI", "redis://127.0.0.1:16379/0")));
    }

    /**
     * 创建 RPC 配置，仍需应用注册 codec、handler、安全验证与生命周期。
     * @param client 非空客户端 ID。 @param group 非空消费组。 @param prefix 非空 topic 前缀。
     * @return 不可空配置；线程安全，不启动 Kafka。
     * @throws IllegalArgumentException 参数非法。
     */
    public KafkaRpcSettings kafka(final String client, final String group, final String prefix) {
        return new KafkaRpcSettings(kafkaBootstrap, client, group, prefix, "reply-" + client,
                128, Duration.ofMillis(100), Duration.ofSeconds(5), Map.of(), Map.of("auto.offset.reset", "earliest"));
    }

    /** @return 不可空本地 Nacos 配置；线程安全，不启动 SDK，不提供认证配置。 */
    public NacosDiscoverySettings nacos() { return NacosDiscoverySettings.of(nacosAddress); }

    private static String setting(final String name, final String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
