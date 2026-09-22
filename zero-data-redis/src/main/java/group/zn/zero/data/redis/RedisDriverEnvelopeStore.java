package group.zn.zero.data.redis;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeCodec;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore;
import group.zn.zero.data.error.DataErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import redis.clients.jedis.RedisClient;

/**
 * Redis driver-backed snapshot/index/journal 信封存储。
 *
 * @author zn
 */
public final class RedisDriverEnvelopeStore implements ZeroDataEnvelopeStore {

    /**
     * Redis 条件保存脚本。
     */
    private static final byte[] SAVE_IF_VERSION_SCRIPT = """
            local types = {'string', 'string', 'set', 'set', 'list'}
            for i = 1, #KEYS do
              local actual = redis.call('TYPE', KEYS[i]).ok
              if actual ~= 'none' and actual ~= types[i] then
                return redis.error_reply('WRONGTYPE data key type mismatch')
              end
            end
            local current = redis.call('GET', KEYS[2])
            if ARGV[1] == '0' then
              if current then
                return 0
              end
            else
              if (not current) or current ~= ARGV[1] then
                return 0
              end
            end
            redis.call('SET', KEYS[1], ARGV[3])
            redis.call('SET', KEYS[2], ARGV[2])
            redis.call('SADD', KEYS[3], ARGV[4])
            redis.call('SADD', KEYS[4], KEYS[3])
            redis.call('RPUSH', KEYS[5], ARGV[5])
            return 1
            """.getBytes(StandardCharsets.UTF_8);

    /**
     * 原子删除快照、版本、索引和删除日志脚本。
     */
    private static final byte[] DELETE_SCRIPT = """
            local types = {'string', 'string', 'set', 'list'}
            for i = 1, #KEYS do
              local actual = redis.call('TYPE', KEYS[i]).ok
              if actual ~= 'none' and actual ~= types[i] then
                return redis.error_reply('WRONGTYPE data key type mismatch')
              end
            end
            if not redis.call('GET', KEYS[1]) then
              return 0
            end
            if redis.call('GET', KEYS[2]) ~= ARGV[3] then
              return -1
            end
            redis.call('DEL', KEYS[1], KEYS[2])
            redis.call('SREM', KEYS[3], ARGV[1])
            redis.call('RPUSH', KEYS[4], ARGV[2])
            return 1
            """.getBytes(StandardCharsets.UTF_8);

    /**
     * 数据命名空间。
     */
    private final String namespace;

    /**
     * 数据集合名称。
     */
    private final String collection;

    /**
     * Redis client。
     */
    private final RedisClient client;

    /**
     * Redis key 策略。
     */
    private final RedisDataKeyStrategy keyStrategy;

    /**
     * 信封 codec。
     */
    private final ZeroDataEnvelopeCodec envelopeCodec;

    /**
     * journal entry codec。
     */
    private final RedisDataJournalEntryCodec journalEntryCodec;

    /**
     * 本地磁盘化追加日志。
     */
    private final LocalDiskDataJournal localJournal;

    /**
     * 创建 Redis driver-backed 信封存储。
     *
     * @param namespace 数据命名空间；不可为空。
     * @param collection 数据集合名称；不可为空。
     * @param client Redis client；不可为空。
     * @param keyStrategy Redis key 策略；不可为空。
     * @param localJournal 本地磁盘化追加日志；可为空。
     * @throws NullPointerException 当必要参数为空时抛出。
     */
    public RedisDriverEnvelopeStore(
            final String namespace,
            final String collection,
            final RedisClient client,
            final RedisDataKeyStrategy keyStrategy,
            final LocalDiskDataJournal localJournal) {
        this.namespace = requireText(namespace, "namespace");
        this.collection = requireText(collection, "collection");
        this.client = Objects.requireNonNull(client, "client");
        this.keyStrategy = Objects.requireNonNull(keyStrategy, "keyStrategy");
        this.localJournal = localJournal;
        this.envelopeCodec = new ZeroDataEnvelopeCodec();
        this.journalEntryCodec = new RedisDataJournalEntryCodec();
    }

    /**
     * 根据编码 ID 查询信封。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @return 查询结果；不可为空；可能为空；线程安全性由 Jedis 保证。
     */
    @Override
    public Optional<ZeroDataEnvelope> findById(final String id) {
        try {
            byte[] bytes = client.get(bytes(dataKey(id)));
            return bytes == null ? Optional.empty() : Optional.of(envelopeCodec.decode(bytes));
        } catch (RuntimeException ex) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "redis read failed", ex);
        }
    }

    /**
     * 查询全部信封。
     *
     * @return 信封列表；不可为空；可能为空；线程安全性由 Jedis 保证。
     */
    @Override
    public List<ZeroDataEnvelope> findAll() {
        try {
            List<ZeroDataEnvelope> results = new ArrayList<>();
            for (String indexKey : client.smembers(collectionIndexKey())) {
                Set<String> ids = client.smembers(indexKey);
                for (String id : ids) {
                    findById(id).ifPresent(results::add);
                }
            }
            return List.copyOf(results);
        } catch (RuntimeException ex) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "redis read all failed", ex);
        }
    }

    /**
     * 保存信封。
     *
     * @param envelope 信封；不可为空。
     */
    @Override
    public void save(final ZeroDataEnvelope envelope) {
        try {
            ZeroDataEnvelope current = validateEnvelope(envelope);
            boolean saved = saveIfVersion(current, Math.max(0L, current.version() - 1L));
            if (!saved) {
                throw ZeroException.of(DataErrorCode.VERSION_CONFLICT, "redis version conflict", null);
            }
        } catch (RuntimeException ex) {
            if (ex instanceof ZeroException zeroException) {
                throw zeroException;
            }
            throw ZeroException.of(DataErrorCode.WRITE_FAILED, "redis write failed", ex);
        }
    }

    /**
     * 按期望版本条件保存信封。
     *
     * @param envelope 信封；不可为空。
     * @param expectedVersion 期望当前版本；必须大于等于 0。
     * @return true 表示保存成功；false 表示版本条件不满足；线程安全性由 Redis 单命令脚本保证。
     */
    @Override
    public boolean saveIfVersion(final ZeroDataEnvelope envelope, final long expectedVersion) {
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        ZeroDataEnvelope current = validateEnvelope(envelope);
        RedisDataJournalEntry entry = RedisDataJournalEntry.put(current, envelopeCodec);
        try {
            RedisDataSnapshot snapshot = RedisDataSnapshot.fromEnvelope(current, keyStrategy, envelopeCodec);
            Object result = client.eval(
                    SAVE_IF_VERSION_SCRIPT,
                    List.of(
                            bytes(snapshot.dataKey()),
                            bytes(versionKey(snapshot.id())),
                            bytes(snapshot.indexKey()),
                            bytes(collectionIndexKey()),
                            bytes(snapshot.journalKey())),
                    List.of(
                            bytes(String.valueOf(expectedVersion)),
                            bytes(String.valueOf(current.version())),
                            snapshot.envelopeBytes(),
                            bytes(snapshot.id()),
                            journalEntryCodec.encode(entry)));
            return result instanceof Number number && number.longValue() == 1L;
        } catch (RuntimeException ex) {
            if (localJournal != null) {
                try {
                    appendLocal(entry);
                } catch (RuntimeException journalFailure) {
                    ex.addSuppressed(journalFailure);
                }
            }
            throw ZeroException.of(DataErrorCode.WRITE_FAILED, "redis conditional write failed", ex);
        }
    }

    /**
     * 根据编码 ID 删除信封。
     *
     * @param id 编码后的存储 ID；不可为空。
     */
    @Override
    public void deleteById(final String id) {
        try {
            String currentId = Objects.requireNonNull(id, "id");
            String dataKey = dataKey(currentId);
            byte[] envelopeBytes = client.get(bytes(dataKey));
            if (envelopeBytes == null) {
                return;
            }
            ZeroDataEnvelope envelope = envelopeCodec.decode(envelopeBytes);
            String indexKey = indexKey(currentId);
            String journalKey = journalKey(currentId);
            RedisDataJournalEntry entry = RedisDataJournalEntry.delete(
                    namespace,
                    collection,
                    currentId,
                    envelope.version(),
                    System.currentTimeMillis());
            Object result = client.eval(
                    DELETE_SCRIPT,
                    List.of(
                            bytes(dataKey),
                            bytes(versionKey(currentId)),
                            bytes(indexKey),
                            bytes(journalKey)),
                    List.of(bytes(currentId), journalEntryCodec.encode(entry),
                            bytes(String.valueOf(envelope.version()))));
            if (result instanceof Number number && number.longValue() == -1L) {
                throw ZeroException.of(DataErrorCode.VERSION_CONFLICT, "redis delete version conflict", null);
            }
            if (!(result instanceof Number number) || number.longValue() < 0L || number.longValue() > 1L) {
                throw ZeroException.of(DataErrorCode.DELETE_FAILED, "unexpected redis delete result", null);
            }
        } catch (RuntimeException ex) {
            if (ex instanceof ZeroException zeroException) {
                throw zeroException;
            }
            throw ZeroException.of(DataErrorCode.DELETE_FAILED, "redis delete failed", ex);
        }
    }

    /**
     * 统计信封数量。
     *
     * @return 信封数量；线程安全性由 Jedis 保证。
     */
    @Override
    public long count() {
        try {
            long total = 0L;
            for (String indexKey : client.smembers(collectionIndexKey())) {
                total += client.scard(indexKey);
            }
            return total;
        } catch (RuntimeException ex) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "redis count failed", ex);
        }
    }

    /**
     * 返回指定编码 ID 的 data key。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @return Redis data key；不可为空；线程安全。
     */
    public String dataKey(final String id) {
        return keyStrategy.dataKey(namespace, collection, Objects.requireNonNull(id, "id"));
    }

    /**
     * 返回指定编码 ID 的 index key。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @return Redis index key；不可为空；线程安全。
     */
    public String indexKey(final String id) {
        return keyStrategy.indexKey(namespace, collection, Objects.requireNonNull(id, "id"));
    }

    /**
     * 返回指定编码 ID 的 journal key。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @return Redis journal key；不可为空；线程安全。
     */
    public String journalKey(final String id) {
        return keyStrategy.journalKey(namespace, collection, Objects.requireNonNull(id, "id"));
    }

    /**
     * 返回指定编码 ID 的版本 key。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @return Redis version key；不可为空；线程安全。
     */
    public String versionKey(final String id) {
        return dataKey(id) + ":version";
    }

    private ZeroDataEnvelope validateEnvelope(final ZeroDataEnvelope envelope) {
        ZeroDataEnvelope current = Objects.requireNonNull(envelope, "envelope");
        if (!namespace.equals(current.namespace()) || !collection.equals(current.collection())) {
            throw ZeroException.of(DataErrorCode.MAPPING_INVALID, "redis data envelope collection mismatch", null);
        }
        return current;
    }

    private String collectionIndexKey() {
        return "zero:indexes:{" + namespace + ":" + collection + "}";
    }

    private void appendLocal(final RedisDataJournalEntry entry) {
        if (localJournal != null) {
            localJournal.append(entry);
        }
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
