package group.zn.zero.runtime.production;

/** Stable diagnostic identities, independent of the convenience Starter. */
public final class ProductionAdapterNames {
    public static final String ADAPTER_KAFKA_RPC = "kafka-rpc";
    public static final String ADAPTER_MONGO_DATA = "mongo-data";
    public static final String ADAPTER_REDIS_DATA = "redis-data";
    public static final String ADAPTER_REDIS_CACHE = "redis-cache";
    public static final String ADAPTER_POSTGRESQL_DATA = "postgresql-data";
    public static final String ADAPTER_NACOS_DISCOVERY = "nacos-discovery";
    public static final String ADAPTER_NETWORK_LIFECYCLE = "network-lifecycle";

    private ProductionAdapterNames() {
    }
}
