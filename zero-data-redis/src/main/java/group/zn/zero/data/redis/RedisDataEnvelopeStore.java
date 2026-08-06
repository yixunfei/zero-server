package group.zn.zero.data.redis;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeCodec;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore;
import group.zn.zero.data.error.DataErrorCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Redis snapshot/index/journal 形态的信封存储。
 *
 * <p>当前实现使用本地内存结构模拟 Redis key/value、bucket set 和 stream/list journal；
 * 后续接入官方 Redis driver 时保持外部 Repository 语义不变。
 *
 * @author zn
 */
public final class RedisDataEnvelopeStore implements ZeroDataEnvelopeStore {

    /**
     * 数据命名空间。
     */
    private final String namespace;

    /**
     * 数据集合名称。
     */
    private final String collection;

    /**
     * Redis key 策略。
     */
    private final RedisDataKeyStrategy keyStrategy;

    /**
     * 信封 codec。
     */
    private final ZeroDataEnvelopeCodec envelopeCodec;

    /**
     * 本地磁盘化追加日志。
     */
    private final LocalDiskDataJournal localJournal;

    /**
     * 快照存储，key 为编码后的对象 ID。
     */
    private final Map<String, RedisDataSnapshot> snapshots = new LinkedHashMap<>();

    /**
     * bucket 索引，key 为 Redis index key。
     */
    private final Map<String, Set<String>> indexIds = new LinkedHashMap<>();

    /**
     * 追加日志，key 为 Redis journal key。
     */
    private final Map<String, List<RedisDataJournalEntry>> journalEntries = new LinkedHashMap<>();

    /**
     * 创建 Redis 信封存储。
     *
     * @param namespace 数据命名空间；不可为空。
     * @param collection 数据集合名称；不可为空。
     */
    public RedisDataEnvelopeStore(final String namespace, final String collection) {
        this(namespace, collection, new DefaultRedisDataKeyStrategy(), null, new ZeroDataEnvelopeCodec());
    }

    /**
     * 创建 Redis 信封存储。
     *
     * @param namespace 数据命名空间；不可为空。
     * @param collection 数据集合名称；不可为空。
     * @param keyStrategy Redis key 策略；不可为空。
     * @param localJournal 本地磁盘化追加日志；可为空。
     * @param envelopeCodec 信封 codec；不可为空。
     * @throws NullPointerException 当必要参数为空时抛出。
     */
    public RedisDataEnvelopeStore(
            final String namespace,
            final String collection,
            final RedisDataKeyStrategy keyStrategy,
            final LocalDiskDataJournal localJournal,
            final ZeroDataEnvelopeCodec envelopeCodec) {
        this.namespace = requireText(namespace, "namespace");
        this.collection = requireText(collection, "collection");
        this.keyStrategy = Objects.requireNonNull(keyStrategy, "keyStrategy");
        this.localJournal = localJournal;
        this.envelopeCodec = Objects.requireNonNull(envelopeCodec, "envelopeCodec");
    }

    /**
     * 根据编码 ID 查询信封。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @return 查询结果；不可为空；可能为空；线程安全。
     */
    @Override
    public synchronized Optional<ZeroDataEnvelope> findById(final String id) {
        return snapshot(id).map(snapshot -> envelopeCodec.decode(snapshot.envelopeBytes()));
    }

    /**
     * 查询全部信封。
     *
     * @return 信封列表；不可为空；可能为空；有序；线程安全。
     */
    @Override
    public synchronized List<ZeroDataEnvelope> findAll() {
        return snapshots.values().stream()
                .map(snapshot -> envelopeCodec.decode(snapshot.envelopeBytes()))
                .toList();
    }

    /**
     * 保存信封。
     *
     * @param envelope 信封；不可为空。
     */
    @Override
    public synchronized void save(final ZeroDataEnvelope envelope) {
        ZeroDataEnvelope current = validateEnvelope(envelope);
        RedisDataSnapshot snapshot = RedisDataSnapshot.fromEnvelope(current, keyStrategy, envelopeCodec);
        snapshots.put(snapshot.id(), snapshot);
        indexIds.computeIfAbsent(snapshot.indexKey(), key -> new LinkedHashSet<>()).add(snapshot.id());
        RedisDataJournalEntry entry = RedisDataJournalEntry.put(current, envelopeCodec);
        journalEntries.computeIfAbsent(snapshot.journalKey(), key -> new ArrayList<>()).add(entry);
        appendLocal(entry);
    }

    /**
     * 按期望版本条件保存信封。
     *
     * @param envelope 信封；不可为空。
     * @param expectedVersion 期望当前版本；必须大于等于 0。
     * @return true 表示保存成功；false 表示版本条件不满足；线程安全。
     */
    @Override
    public synchronized boolean saveIfVersion(final ZeroDataEnvelope envelope, final long expectedVersion) {
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        ZeroDataEnvelope current = validateEnvelope(envelope);
        RedisDataSnapshot previous = snapshots.get(current.id());
        if (expectedVersion == 0L) {
            if (previous != null) {
                return false;
            }
        } else if (previous == null || previous.version() != expectedVersion) {
            return false;
        }
        save(current);
        return true;
    }

    /**
     * 根据编码 ID 删除信封。
     *
     * @param id 编码后的存储 ID；不可为空。
     */
    @Override
    public synchronized void deleteById(final String id) {
        String currentId = Objects.requireNonNull(id, "id");
        RedisDataSnapshot removed = snapshots.remove(currentId);
        if (removed == null) {
            return;
        }
        Set<String> ids = indexIds.get(removed.indexKey());
        if (ids != null) {
            ids.remove(currentId);
        }
        RedisDataJournalEntry entry = RedisDataJournalEntry.delete(
                namespace,
                collection,
                currentId,
                removed.version(),
                System.currentTimeMillis());
        journalEntries.computeIfAbsent(removed.journalKey(), key -> new ArrayList<>()).add(entry);
        appendLocal(entry);
    }

    /**
     * 统计信封数量。
     *
     * @return 信封数量；线程安全。
     */
    @Override
    public synchronized long count() {
        return snapshots.size();
    }

    /**
     * 根据编码 ID 查询 Redis 快照。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @return Redis 快照；为空表示不存在；线程安全。
     */
    public synchronized Optional<RedisDataSnapshot> snapshot(final String id) {
        return Optional.ofNullable(snapshots.get(Objects.requireNonNull(id, "id")));
    }

    /**
     * 查询指定 Redis index key 下的 ID 集合。
     *
     * @param indexKey Redis index key；不可为空。
     * @return ID 集合；不可为空；有序；可能为空；线程安全。
     */
    public synchronized Set<String> indexIds(final String indexKey) {
        Set<String> ids = indexIds.getOrDefault(Objects.requireNonNull(indexKey, "indexKey"), Set.of());
        return Collections.unmodifiableSet(new LinkedHashSet<>(ids));
    }

    /**
     * 查询指定 Redis journal key 下的追加日志。
     *
     * @param journalKey Redis journal key；不可为空。
     * @return 追加日志列表；不可为空；有序；可能为空；线程安全。
     */
    public synchronized List<RedisDataJournalEntry> journalEntries(final String journalKey) {
        return List.copyOf(journalEntries.getOrDefault(
                Objects.requireNonNull(journalKey, "journalKey"),
                List.of()));
    }

    private ZeroDataEnvelope validateEnvelope(final ZeroDataEnvelope envelope) {
        ZeroDataEnvelope current = Objects.requireNonNull(envelope, "envelope");
        if (!namespace.equals(current.namespace()) || !collection.equals(current.collection())) {
            throw ZeroException.of(DataErrorCode.MAPPING_INVALID, "redis data envelope collection mismatch", null);
        }
        return current;
    }

    private void appendLocal(final RedisDataJournalEntry entry) {
        if (localJournal != null) {
            localJournal.append(entry);
        }
    }

    private String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
