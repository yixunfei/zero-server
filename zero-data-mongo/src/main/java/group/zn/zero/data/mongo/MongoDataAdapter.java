package group.zn.zero.data.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import group.zn.zero.data.adapter.AbstractRepositoryAdapter;
import group.zn.zero.data.envelope.ZeroDataEntityCodec;
import group.zn.zero.data.envelope.EnvelopeRepositoryFactory;
import group.zn.zero.data.envelope.ZeroDataEnvelopeCrudRepository;
import group.zn.zero.data.mapping.ZeroDataObjectMetadata;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB 数据适配器。
 *
 * @author zn
 */
public final class MongoDataAdapter extends AbstractRepositoryAdapter {

    /**
     * 创建 MongoDB 数据适配器。
     */
    public MongoDataAdapter() {
        super("mongo");
    }

    /** The caller owns the client and must close this factory before releasing that client. */
    public EnvelopeRepositoryFactory repositoryFactory(final MongoClient client, final String databaseName) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(databaseName, "databaseName");
        return new EnvelopeRepositoryFactory(metadata -> new MongoDriverEnvelopeStore(
                client.getDatabase(databaseName), metadata.namespace(), metadata.collection()));
    }

    /**
     * 注册基于 zcode 信封的 MongoDB Repository。
     *
     * @param name 仓库名称；不可为空。
     * @param metadata 映射元数据；不可为空。
     * @param payloadCodec payload codec；不可为空。
     * @param codecVersion payload codec 版本；必须大于 0。
     * @param <ID> 主键类型。
     * @param <T> 实体类型。
     * @return 已注册仓库；不可为空；线程安全。
     */
    public <ID, T extends VersionedEntity<ID>> ZeroDataEnvelopeCrudRepository<ID, T> registerZcodeRepository(
            final String name,
            final ZeroDataObjectMetadata metadata,
            final ZeroPayloadCodec<T> payloadCodec,
            final int codecVersion) {
        ZeroDataEntityCodec<ID, T> entityCodec = new ZeroDataEntityCodec<>(
                Objects.requireNonNull(metadata, "metadata"),
                Objects.requireNonNull(payloadCodec, "payloadCodec"),
                codecVersion);
        ZeroDataEnvelopeCrudRepository<ID, T> repository =
                new ZeroDataEnvelopeCrudRepository<>(entityCodec, new MongoDataEnvelopeStore());
        registerRepository(name, repository);
        return repository;
    }

    /**
     * 注册基于 MongoDB driver 的 zcode Repository。
     *
     * @param name 仓库名称；不可为空。
     * @param client MongoDB client；不可为空。
     * @param databaseName 数据库名称；不可为空。
     * @param metadata 映射元数据；不可为空。
     * @param payloadCodec payload codec；不可为空。
     * @param codecVersion payload codec 版本；必须大于 0。
     * @param <ID> 主键类型。
     * @param <T> 实体类型。
     * @return 已注册仓库；不可为空；线程安全。
     */
    public <ID, T extends VersionedEntity<ID>> ZeroDataEnvelopeCrudRepository<ID, T> registerDriverRepository(
            final String name,
            final MongoClient client,
            final String databaseName,
            final ZeroDataObjectMetadata metadata,
            final ZeroPayloadCodec<T> payloadCodec,
            final int codecVersion) {
        ZeroDataObjectMetadata currentMetadata = Objects.requireNonNull(metadata, "metadata");
        MongoDatabase database = Objects.requireNonNull(client, "client")
                .getDatabase(Objects.requireNonNull(databaseName, "databaseName"));
        ZeroDataEntityCodec<ID, T> entityCodec = new ZeroDataEntityCodec<>(
                currentMetadata,
                Objects.requireNonNull(payloadCodec, "payloadCodec"),
                codecVersion);
        ZeroDataEnvelopeCrudRepository<ID, T> repository = new ZeroDataEnvelopeCrudRepository<>(
                entityCodec,
                new MongoDriverEnvelopeStore(database, currentMetadata.namespace(), currentMetadata.collection()));
        registerRepository(name, repository);
        return repository;
    }

    /**
     * 创建 MongoDB client。
     *
     * @param settings MongoDB driver 连接配置；不可为空。
     * @return MongoDB client；不可为空；调用方负责关闭。
     */
    public MongoClient createClient(final MongoDriverSettings settings) {
        return MongoClients.create(Objects.requireNonNull(settings, "settings").connectionString());
    }

    /**
     * 使用统一原生超时创建 MongoDB client。
     *
     * <p>超时同时覆盖 server selection、socket connect 与 socket read；MongoDB driver 仅支持毫秒精度，
     * 小于一毫秒的正数会按一毫秒处理。
     *
     * @param settings MongoDB driver 连接配置；不可为空。
     * @param timeout 原生操作超时；必须为正数。
     * @return MongoDB client；不可为空；调用方负责关闭；线程安全。
     * @throws NullPointerException 当配置或超时为空时抛出。
     * @throws IllegalArgumentException 当超时非正数或连接串非法时抛出；异常不会回显连接串。
     */
    public MongoClient createClient(final MongoDriverSettings settings, final Duration timeout) {
        return MongoClients.create(createClientSettings(settings, timeout));
    }

    /**
     * 构造应用原生超时后的 MongoDB client settings。
     *
     * @param settings MongoDB driver 连接配置；不可为空。
     * @param timeout 原生操作超时；必须为正数。
     * @return client settings；不可为空；不可变；线程安全。
     */
    MongoClientSettings createClientSettings(final MongoDriverSettings settings, final Duration timeout) {
        MongoDriverSettings checkedSettings = Objects.requireNonNull(settings, "settings");
        int timeoutMillis = nativeTimeoutMillis(timeout);
        ConnectionString connectionString = parseConnectionString(checkedSettings.connectionString());
        return MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToClusterSettings(builder -> builder.serverSelectionTimeout(
                        timeoutMillis,
                        TimeUnit.MILLISECONDS))
                .applyToSocketSettings(builder -> builder
                        .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS))
                .build();
    }

    private ConnectionString parseConnectionString(final String value) {
        try {
            return new ConnectionString(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid MongoDB connection string");
        }
    }

    private int nativeTimeoutMillis(final Duration timeout) {
        Duration checked = Objects.requireNonNull(timeout, "timeout");
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Duration maximum = Duration.ofMillis(Integer.MAX_VALUE);
        if (checked.compareTo(maximum) >= 0) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(1L, checked.toMillis());
    }
}
