package group.zn.zero.runtime.production;

/**
 * 生产装配模块配置键。
 *
 * <p>本类只定义 opt-in 开关和 production starter 自身使用的键名，不改变
 * `zero-server-starter` 本地默认装配语义。
 *
 * @author zn
 */
public final class ZeroProductionRuntimeConfigKeys {

    /** 单进程按需装配档位；外部 Adapter 仍须显式启用。 */
    public static final String MODE_STANDALONE = "standalone";

    /**
     * 外部测试 profile。
     */
    public static final String MODE_EXTERNAL_TEST = "external-test";

    /**
     * 生产 profile。
     */
    public static final String MODE_PRODUCTION = "production";

    /**
     * 全部 Production Adapter 串行启动的累计预算，单位毫秒。
     */
    public static final String ADAPTER_STARTUP_BUDGET_MILLIS = "zero.adapter.startup-budget-millis";

    /**
     * 单个 Production Adapter 的最大启动 timeout，单位毫秒。
     */
    public static final String ADAPTER_STARTUP_TIMEOUT_MILLIS = "zero.adapter.startup-timeout-millis";

    /**
     * 默认累计启动预算，单位毫秒。
     */
    public static final int DEFAULT_ADAPTER_STARTUP_BUDGET_MILLIS = 60_000;

    /**
     * 默认单 Adapter 启动 timeout，单位毫秒。
     */
    public static final int DEFAULT_ADAPTER_STARTUP_TIMEOUT_MILLIS = 10_000;

    /**
     * 生产 TCP 连接生命周期显式启用开关。
     */
    public static final String NETWORK_LIFECYCLE_ENABLED = "zero.net.lifecycle.enabled";

    /**
     * 生产网络低基数 listener 名称。
     */
    public static final String NETWORK_LISTENER = "zero.net.lifecycle.listener";

    /**
     * 握手超时毫秒。
     */
    public static final String NETWORK_HANDSHAKE_TIMEOUT_MILLIS =
            "zero.net.lifecycle.handshake-timeout-millis";

    /**
     * 鉴权超时毫秒。
     */
    public static final String NETWORK_AUTHENTICATION_TIMEOUT_MILLIS =
            "zero.net.lifecycle.authentication-timeout-millis";

    /**
     * 心跳间隔毫秒。
     */
    public static final String NETWORK_HEARTBEAT_INTERVAL_MILLIS =
            "zero.net.lifecycle.heartbeat-interval-millis";

    /**
     * 允许丢失心跳次数。
     */
    public static final String NETWORK_ALLOWED_MISSED_HEARTBEATS =
            "zero.net.lifecycle.allowed-missed-heartbeats";

    /**
     * 重连窗口毫秒。
     */
    public static final String NETWORK_RECONNECT_WINDOW_MILLIS =
            "zero.net.lifecycle.reconnect-window-millis";

    /**
     * 单连接入站 frame 预算。
     */
    public static final String NETWORK_MAX_INBOUND_FRAMES = "zero.net.lifecycle.max-inbound-frames";

    /**
     * production 示例每 IP 每秒新连接数。
     */
    public static final String NETWORK_PER_IP_PERMITS_PER_SECOND =
            "zero.net.lifecycle.per-ip-permits-per-second";

    /**
     * production 示例每 IP 新连接突发容量。
     */
    public static final String NETWORK_PER_IP_BURST_CAPACITY =
            "zero.net.lifecycle.per-ip-burst-capacity";

    /**
     * 有界 IP 限流槽数量。
     */
    public static final String NETWORK_RATE_LIMIT_SLOTS = "zero.net.lifecycle.rate-limit-slots";

    /**
     * 默认 production listener 名称。
     */
    public static final String DEFAULT_NETWORK_LISTENER = "production-tcp";

    /**
     * 默认每 IP 每秒新连接数；只属于 production starter 示例策略。
     */
    public static final int DEFAULT_NETWORK_PER_IP_PERMITS_PER_SECOND = 20;

    /**
     * 默认每 IP 新连接突发容量；只属于 production starter 示例策略。
     */
    public static final int DEFAULT_NETWORK_PER_IP_BURST_CAPACITY = 40;

    /**
     * 默认有界限流槽数量。
     */
    public static final int DEFAULT_NETWORK_RATE_LIMIT_SLOTS = 16_384;

    /**
     * Kafka RPC 显式启用开关。
     */
    public static final String ADAPTER_RPC_KAFKA_ENABLED = "zero.adapter.rpc.kafka.enabled";

    /**
     * Kafka broker 地址配置键。
     */
    public static final String RPC_KAFKA_BOOTSTRAP_SERVERS = "zero.rpc.kafka.bootstrap-servers";

    /**
     * Kafka broker 地址环境变量。
     */
    public static final String ENV_RPC_KAFKA_BOOTSTRAP_SERVERS = "ZERO_KAFKA_BOOTSTRAP_SERVERS";

    /**
     * Kafka clientId 配置键。
     */
    public static final String RPC_KAFKA_CLIENT_ID = "zero.rpc.kafka.client-id";

    /**
     * Kafka consumer group 配置键。
     */
    public static final String RPC_KAFKA_CONSUMER_GROUP_ID = "zero.rpc.kafka.consumer-group-id";

    /**
     * Kafka RPC topic 前缀配置键。
     */
    public static final String RPC_KAFKA_TOPIC_PREFIX = "zero.rpc.kafka.topic-prefix";

    /**
     * Kafka RPC reply topic 配置键。
     */
    public static final String RPC_KAFKA_REPLY_TOPIC = "zero.rpc.kafka.reply-topic";

    /**
     * Kafka RPC pending 容量配置键。
     */
    public static final String RPC_KAFKA_PENDING_CAPACITY = "zero.rpc.kafka.pending-capacity";

    /**
     * Kafka poll 超时毫秒配置键。
     */
    public static final String RPC_KAFKA_POLL_TIMEOUT_MILLIS = "zero.rpc.kafka.poll-timeout-millis";

    /**
     * Kafka close 超时毫秒配置键。
     */
    public static final String RPC_KAFKA_CLOSE_TIMEOUT_MILLIS = "zero.rpc.kafka.close-timeout-millis";

    /**
     * MongoDB data 显式启用开关。
     */
    public static final String ADAPTER_DATA_MONGO_ENABLED = "zero.adapter.data.mongo.enabled";

    /**
     * Redis data 显式启用开关。
     */
    public static final String ADAPTER_DATA_REDIS_ENABLED = "zero.adapter.data.redis.enabled";

    /**
     * Redis cache 显式启用开关。
     */
    public static final String ADAPTER_CACHE_REDIS_ENABLED = "zero.adapter.cache.redis.enabled";

    /**
     * Redis cache 命名空间配置键。
     */
    public static final String ADAPTER_CACHE_REDIS_NAMESPACE = "zero.adapter.cache.redis.namespace";

    /**
     * Redis cache 名称配置键。
     */
    public static final String ADAPTER_CACHE_REDIS_CACHE_NAME = "zero.adapter.cache.redis.cache-name";

    /**
     * PostgreSQL data 显式启用开关。
     */
    public static final String ADAPTER_DATA_POSTGRESQL_ENABLED = "zero.adapter.data.postgresql.enabled";

    private ZeroProductionRuntimeConfigKeys() {
    }
}
