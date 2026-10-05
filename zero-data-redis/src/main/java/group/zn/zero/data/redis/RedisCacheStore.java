package group.zn.zero.data.redis;

import group.zn.zero.cache.CacheErrorCode;
import group.zn.zero.cache.CacheKeyCodec;
import group.zn.zero.cache.CacheKeyCodecs;
import group.zn.zero.cache.CacheStore;
import group.zn.zero.cache.CacheStoreEntry;
import group.zn.zero.cache.CacheValueCodec;
import group.zn.zero.core.error.ZeroException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import redis.clients.jedis.RedisClient;

/**
 * Redis driver-backed 二级缓存存储。
 *
 * <p>该实现使用独立 cache key namespace，不复用数据快照、索引或追加日志 key。
 * Jedis client 是同步驱动；生产环境应由调用方在 Actor 线程之外包装执行边界。
 *
 * @param <K> 缓存键类型。
 * @param <V> 缓存值类型。
 * @author zn
 */
public final class RedisCacheStore<K, V> implements CacheStore<K, V> {

    /**
     * 无条件写入脚本。
     */
    private static final byte[] PUT_SCRIPT = """
            redis.call('PSETEX', KEYS[1], ARGV[2], ARGV[3])
            redis.call('PSETEX', KEYS[2], ARGV[2], ARGV[1])
            return 1
            """.getBytes(StandardCharsets.UTF_8);

    /**
     * 条件写入脚本。
     */
    private static final byte[] PUT_IF_VERSION_SCRIPT = """
            local current = redis.call('GET', KEYS[2])
            if current == '0' and ARGV[1] == '0' then
              local previous = redis.call('GET', KEYS[1])
              if previous and string.sub(previous, 1, 8) >= string.sub(ARGV[3], 1, 8) then
                return 0
              end
            elseif current and (#current > #ARGV[1] or (#current == #ARGV[1] and current >= ARGV[1])) then
                return 0
            end
            redis.call('PSETEX', KEYS[1], ARGV[2], ARGV[3])
            redis.call('PSETEX', KEYS[2], ARGV[2], ARGV[1])
            return 1
            """.getBytes(StandardCharsets.UTF_8);

    /**
     * 条件失效脚本。
     */
    private static final byte[] INVALIDATE_IF_VERSION_SCRIPT = """
            local current = redis.call('GET', KEYS[2])
            if (not current) or #current < #ARGV[1] or (#current == #ARGV[1] and current <= ARGV[1]) then
              redis.call('DEL', KEYS[1])
              redis.call('DEL', KEYS[2])
              return 1
            end
            return 0
            """.getBytes(StandardCharsets.UTF_8);

    /**
     * Redis client。
     */
    private final RedisClient client;

    /**
     * 命名空间。
     */
    private final String namespace;

    /**
     * 缓存名称。
     */
    private final String cacheName;

    /**
     * schema 版本。
     */
    private final int schemaVersion;

    /**
     * key 策略。
     */
    private final RedisCacheKeyStrategy keyStrategy;

    /**
     * 业务 key 编码器。
     */
    private final Function<K, String> keyEncoder;

    /**
     * value codec。
     */
    private final CacheValueCodec<V> valueCodec;

    /**
     * envelope codec。
     */
    private final RedisCacheEnvelopeCodec envelopeCodec;

    /** Redis 同步驱动的受管执行域。 */
    private final Executor ioExecutor;

    /** 兼容旧调用方的异步执行器；生产装配应显式传入 starter 执行域。 */
    private static final Executor COMPATIBILITY_EXECUTOR = ForkJoinPool.commonPool();

    /** 兼容旧构造路径，使用公共异步执行器避免调用线程阻塞。 */
    @Deprecated
    public RedisCacheStore(final RedisClient client, final String namespace, final String cacheName,
            final CacheValueCodec<V> valueCodec) {
        this(client, namespace, cacheName, 1, new DefaultRedisCacheKeyStrategy(), CacheKeyCodecs.defaults(),
                valueCodec, new RedisCacheEnvelopeCodec(), COMPATIBILITY_EXECUTOR);
    }

    /** 兼容旧构造路径，使用公共异步执行器避免调用线程阻塞。 */
    @Deprecated
    public RedisCacheStore(final RedisClient client, final String namespace, final String cacheName,
            final CacheKeyCodec<K> keyCodec, final CacheValueCodec<V> valueCodec) {
        this(client, namespace, cacheName, 1, new DefaultRedisCacheKeyStrategy(), keyCodec, valueCodec,
                new RedisCacheEnvelopeCodec(), COMPATIBILITY_EXECUTOR);
    }

    /** 兼容旧构造路径，使用公共异步执行器避免调用线程阻塞。 */
    @Deprecated
    public RedisCacheStore(final RedisClient client, final String namespace, final String cacheName,
            final int schemaVersion, final RedisCacheKeyStrategy keyStrategy, final CacheKeyCodec<K> keyCodec,
            final CacheValueCodec<V> valueCodec, final RedisCacheEnvelopeCodec envelopeCodec) {
        this(client, namespace, cacheName, schemaVersion, keyStrategy, keyCodec, valueCodec, envelopeCodec,
                COMPATIBILITY_EXECUTOR);
    }

    /** 兼容底层 key 编码器构造路径，使用公共异步执行器。 */
    @Deprecated
    public RedisCacheStore(final RedisClient client, final String namespace, final String cacheName,
            final int schemaVersion, final RedisCacheKeyStrategy keyStrategy, final Function<K, String> keyEncoder,
            final CacheValueCodec<V> valueCodec, final RedisCacheEnvelopeCodec envelopeCodec) {
        this(client, namespace, cacheName, schemaVersion, keyStrategy, keyEncoder, valueCodec, envelopeCodec,
                COMPATIBILITY_EXECUTOR);
    }

    /** 使用指定受管执行域创建 Redis cache store。 */
    public RedisCacheStore(
            final RedisClient client,
            final String namespace,
            final String cacheName,
            final int schemaVersion,
            final RedisCacheKeyStrategy keyStrategy,
            final CacheKeyCodec<K> keyCodec,
            final CacheValueCodec<V> valueCodec,
            final RedisCacheEnvelopeCodec envelopeCodec,
            final Executor ioExecutor) {
        this(
                client,
                namespace,
                cacheName,
                schemaVersion,
                keyStrategy,
                Objects.requireNonNull(keyCodec, "keyCodec")::encode,
                valueCodec,
                envelopeCodec,
                ioExecutor);
    }

    /** 使用指定受管执行域创建 Redis cache store。 */
    public RedisCacheStore(
            final RedisClient client,
            final String namespace,
            final String cacheName,
            final int schemaVersion,
            final RedisCacheKeyStrategy keyStrategy,
            final Function<K, String> keyEncoder,
            final CacheValueCodec<V> valueCodec,
            final RedisCacheEnvelopeCodec envelopeCodec,
            final Executor ioExecutor) {
        this.client = Objects.requireNonNull(client, "client");
        this.namespace = requireText(namespace, "namespace");
        this.cacheName = requireText(cacheName, "cacheName");
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        this.schemaVersion = schemaVersion;
        this.keyStrategy = Objects.requireNonNull(keyStrategy, "keyStrategy");
        this.keyEncoder = Objects.requireNonNull(keyEncoder, "keyEncoder");
        this.valueCodec = Objects.requireNonNull(valueCodec, "valueCodec");
        this.envelopeCodec = Objects.requireNonNull(envelopeCodec, "envelopeCodec");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    /**
     * 读取缓存条目。
     *
     * @param key 缓存键；不可为空。
     * @return 缓存条目；为空表示未命中；线程安全性由 Jedis client 保证。
     */
    @Override
    public CompletionStage<Optional<CacheStoreEntry<V>>> get(final K key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = client.get(bytes(valueKey(key)));
                if (bytes == null) return Optional.empty();
                return Optional.of(toEntry(envelopeCodec.decode(bytes)));
            } catch (RuntimeException ex) {
                throw wrap(CacheErrorCode.READ_FAILED, "redis cache read failed", ex);
            }
        }, ioExecutor);
    }

    /**
     * 写入缓存条目。
     *
     * @param key 缓存键；不可为空。
     * @param entry 缓存条目；不可为空。
     * @return 写入完成信号；不可为空；线程安全性由 Redis 脚本保证。
     */
    @Override
    public CompletionStage<Void> put(final K key, final CacheStoreEntry<V> entry) {
        return CompletableFuture.runAsync(() -> {
            try {
                RedisCacheEnvelope envelope = toEnvelope(entry);
                executeWriteScript(PUT_SCRIPT, key, entry, envelope);
            } catch (RuntimeException ex) {
                throw wrap(CacheErrorCode.WRITE_FAILED, "redis cache write failed", ex);
            }
        }, ioExecutor);
    }

    /**
     * 按实体版本条件写入缓存条目。
     *
     * @param key 缓存键；不可为空。
     * @param entry 缓存条目；不可为空。
     * @return true 表示写入成功；false 表示后端已有更新版本；线程安全性由 Redis 脚本保证。
     */
    @Override
    public CompletionStage<Boolean> putIfVersion(final K key, final CacheStoreEntry<V> entry) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                RedisCacheEnvelope envelope = toEnvelope(entry);
                Object result = executeWriteScript(PUT_IF_VERSION_SCRIPT, key, entry, envelope);
                return result instanceof Number number && number.longValue() == 1L;
            } catch (RuntimeException ex) {
                throw wrap(CacheErrorCode.WRITE_FAILED, "redis cache conditional write failed", ex);
            }
        }, ioExecutor);
    }

    /**
     * 失效缓存。
     *
     * @param key 缓存键；不可为空。
     * @return 失效完成信号；不可为空；线程安全性由 Redis 命令保证。
     */
    @Override
    public CompletionStage<Void> invalidate(final K key) {
        return CompletableFuture.runAsync(() -> {
            try {
                client.del(bytes(valueKey(key)), bytes(versionKey(key)));
            } catch (RuntimeException ex) {
                throw wrap(CacheErrorCode.INVALIDATE_FAILED, "redis cache invalidate failed", ex);
            }
        }, ioExecutor);
    }

    /**
     * 按实体版本条件失效缓存。
     *
     * @param key 缓存键；不可为空。
     * @param entityVersion 实体版本；必须大于等于 0。
     * @return true 表示失效成功或无需失效；false 表示后端已有更新版本；线程安全性由 Redis 脚本保证。
     */
    @Override
    public CompletionStage<Boolean> invalidateIfVersion(final K key, final long entityVersion) {
        if (entityVersion < 0L) {
            throw new IllegalArgumentException("entityVersion must be non-negative");
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object result = client.eval(
                        INVALIDATE_IF_VERSION_SCRIPT,
                        List.of(bytes(valueKey(key)), bytes(versionKey(key))),
                        List.of(bytes(String.valueOf(entityVersion))));
                return result instanceof Number number && number.longValue() == 1L;
            } catch (RuntimeException ex) {
                throw wrap(CacheErrorCode.INVALIDATE_FAILED, "redis cache conditional invalidate failed", ex);
            }
        }, ioExecutor);
    }

    /**
     * 返回缓存值 key。
     *
     * @param key 缓存键；不可为空。
     * @return Redis key；不可为空；线程安全。
     */
    public String valueKey(final K key) {
        return keyStrategy.valueKey(namespace, cacheName, encodedKey(key));
    }

    /**
     * 返回缓存版本 key。
     *
     * @param key 缓存键；不可为空。
     * @return Redis key；不可为空；线程安全。
     */
    public String versionKey(final K key) {
        return keyStrategy.versionKey(namespace, cacheName, encodedKey(key));
    }

    private Object executeWriteScript(
            final byte[] script,
            final K key,
            final CacheStoreEntry<V> entry,
            final RedisCacheEnvelope envelope) {
        long ttlMillis = Math.max(1L, entry.expiresAt().toEpochMilli() - Instant.now().toEpochMilli());
        String valueKey = valueKey(key);
        return client.eval(
                script,
                List.of(bytes(valueKey), bytes(versionKey(key))),
                List.of(
                        bytes(String.valueOf(entry.entityVersion())),
                        bytes(String.valueOf(ttlMillis)),
                        envelopeCodec.encode(envelope)));
    }

    private RedisCacheEnvelope toEnvelope(final CacheStoreEntry<V> entry) {
        CacheStoreEntry<V> current = Objects.requireNonNull(entry, "entry");
        byte[] payload = current.negative() ? new byte[0] : valueCodec.encode(current.value());
        return new RedisCacheEnvelope(
                current.cacheVersion(),
                current.entityVersion(),
                schemaVersion,
                valueCodec.name(),
                Instant.now().toEpochMilli(),
                current.expiresAt().toEpochMilli(),
                current.negative(),
                payload);
    }

    private CacheStoreEntry<V> toEntry(final RedisCacheEnvelope envelope) {
        RedisCacheEnvelope current = Objects.requireNonNull(envelope, "envelope");
        if (!valueCodec.name().equals(current.codecName())) {
            throw ZeroException.of(CacheErrorCode.DESERIALIZE_FAILED, "redis cache codec mismatch", null);
        }
        V value = current.negative() ? null : valueCodec.decode(current.payload());
        return new CacheStoreEntry<>(
                value,
                current.cacheVersion(),
                current.entityVersion(),
                Instant.ofEpochMilli(current.expireAtEpochMillis()),
                current.negative());
    }

    private String encodedKey(final K key) {
        String encoded = Objects.requireNonNull(keyEncoder.apply(Objects.requireNonNull(key, "key")), "encodedKey");
        if (encoded.isBlank()) {
            throw new IllegalArgumentException("encodedKey must not be blank");
        }
        return encoded;
    }

    private ZeroException wrap(final CacheErrorCode errorCode, final String message, final RuntimeException ex) {
        if (ex instanceof ZeroException zeroException) {
            return zeroException;
        }
        return ZeroException.of(errorCode, message, ex);
    }

    private byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
