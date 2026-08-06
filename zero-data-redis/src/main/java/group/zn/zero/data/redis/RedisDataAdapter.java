package group.zn.zero.data.redis;

import group.zn.zero.data.adapter.AbstractRepositoryAdapter;
import group.zn.zero.data.envelope.ZeroDataEntityCodec;
import group.zn.zero.data.envelope.ZeroDataEnvelopeCodec;
import group.zn.zero.data.envelope.ZeroDataEnvelopeCrudRepository;
import group.zn.zero.data.mapping.ZeroDataObjectMetadata;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import redis.clients.jedis.RedisClient;

/**
 * Redis 数据适配器。
 *
 * @author zn
 */
public final class RedisDataAdapter extends AbstractRepositoryAdapter {

    /**
     * 是否启用追加式存储过渡模式。
     */
    private volatile boolean appendOnlyMode;

    /**
     * Redis key 策略。
     */
    private final RedisDataKeyStrategy keyStrategy;

    /**
     * 创建 Redis 数据适配器。
     */
    public RedisDataAdapter() {
        this(new DefaultRedisDataKeyStrategy());
    }

    /**
     * 创建 Redis 数据适配器。
     *
     * @param keyStrategy Redis key 策略；不可为空。
     * @throws NullPointerException 当 Redis key 策略为空时抛出。
     */
    public RedisDataAdapter(final RedisDataKeyStrategy keyStrategy) {
        super("redis");
        this.keyStrategy = Objects.requireNonNull(keyStrategy, "keyStrategy");
    }

    /**
     * 返回是否启用追加式存储过渡模式。
     *
     * @return true 表示启用；线程安全。
     */
    public boolean appendOnlyMode() {
        return appendOnlyMode;
    }

    /**
     * 设置追加式存储过渡模式。
     *
     * @param appendOnlyMode 是否启用追加式存储模式。
     */
    public void appendOnlyMode(final boolean appendOnlyMode) {
        this.appendOnlyMode = appendOnlyMode;
    }

    /**
     * 注册基于 zcode 信封的 Redis Repository。
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
        return registerZcodeRepository(name, metadata, payloadCodec, codecVersion, null);
    }

    /**
     * 注册带本地磁盘化追加日志的 zcode Redis Repository。
     *
     * @param name 仓库名称；不可为空。
     * @param metadata 映射元数据；不可为空。
     * @param payloadCodec payload codec；不可为空。
     * @param codecVersion payload codec 版本；必须大于 0。
     * @param localJournal 本地磁盘化追加日志；可为空。
     * @param <ID> 主键类型。
     * @param <T> 实体类型。
     * @return 已注册仓库；不可为空；线程安全。
     */
    public <ID, T extends VersionedEntity<ID>> ZeroDataEnvelopeCrudRepository<ID, T> registerZcodeRepository(
            final String name,
            final ZeroDataObjectMetadata metadata,
            final ZeroPayloadCodec<T> payloadCodec,
            final int codecVersion,
            final LocalDiskDataJournal localJournal) {
        ZeroDataObjectMetadata currentMetadata = Objects.requireNonNull(metadata, "metadata");
        ZeroDataEntityCodec<ID, T> entityCodec = new ZeroDataEntityCodec<>(
                currentMetadata,
                Objects.requireNonNull(payloadCodec, "payloadCodec"),
                codecVersion);
        RedisDataEnvelopeStore store = new RedisDataEnvelopeStore(
                currentMetadata.namespace(),
                currentMetadata.collection(),
                keyStrategy,
                localJournal,
                new ZeroDataEnvelopeCodec());
        ZeroDataEnvelopeCrudRepository<ID, T> repository = new ZeroDataEnvelopeCrudRepository<>(entityCodec, store);
        registerRepository(name, repository);
        return repository;
    }

    /**
     * 注册基于 Redis driver 的 zcode Repository。
     *
     * @param name 仓库名称；不可为空。
     * @param client Redis client；不可为空。
     * @param metadata 映射元数据；不可为空。
     * @param payloadCodec payload codec；不可为空。
     * @param codecVersion payload codec 版本；必须大于 0。
     * @param localJournal 本地磁盘化追加日志；可为空。
     * @param <ID> 主键类型。
     * @param <T> 实体类型。
     * @return 已注册仓库；不可为空；线程安全。
     */
    public <ID, T extends VersionedEntity<ID>> ZeroDataEnvelopeCrudRepository<ID, T> registerDriverRepository(
            final String name,
            final RedisClient client,
            final ZeroDataObjectMetadata metadata,
            final ZeroPayloadCodec<T> payloadCodec,
            final int codecVersion,
            final LocalDiskDataJournal localJournal) {
        ZeroDataObjectMetadata currentMetadata = Objects.requireNonNull(metadata, "metadata");
        ZeroDataEntityCodec<ID, T> entityCodec = new ZeroDataEntityCodec<>(
                currentMetadata,
                Objects.requireNonNull(payloadCodec, "payloadCodec"),
                codecVersion);
        RedisDriverEnvelopeStore store = new RedisDriverEnvelopeStore(
                currentMetadata.namespace(),
                currentMetadata.collection(),
                Objects.requireNonNull(client, "client"),
                keyStrategy,
                localJournal);
        ZeroDataEnvelopeCrudRepository<ID, T> repository = new ZeroDataEnvelopeCrudRepository<>(entityCodec, store);
        registerRepository(name, repository);
        return repository;
    }

    /**
     * 创建 Redis client。
     *
     * @param settings Redis driver 连接配置；不可为空。
     * @return Redis client；不可为空；调用方负责关闭。
     */
    public RedisClient createClient(final RedisDriverSettings settings) {
        return RedisClient.create(URI.create(Objects.requireNonNull(settings, "settings").uri()));
    }

    /**
     * 使用统一原生超时创建 Redis client。
     *
     * @param settings Redis driver 连接配置；不可为空。
     * @param timeout 连接、socket 与阻塞 socket 超时；必须为正数。
     * @return Redis client；不可为空；调用方负责关闭；线程安全性由 Jedis 声明。
     * @throws NullPointerException 当配置或超时为空时抛出。
     * @throws IllegalArgumentException 当 URI 非法或超时非正数时抛出；异常不会回显 URI。
     */
    public RedisClient createClient(final RedisDriverSettings settings, final Duration timeout) {
        return RedisDriverClientFactory.create(settings, timeout);
    }
}
