package group.zn.zero.data.redis;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeCodec;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.model.PageRequest;
import group.zn.zero.data.model.PageResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import redis.clients.jedis.RedisClient;

/**
 * Redis driver-backed snapshot/index/journal 信封存储。
 *
 * @author zn
 */
public final class RedisDriverEnvelopeStore implements ZeroDataEnvelopeStore {

    /** 单次 pipeline 的有界操作数。 */
    private static final int BATCH_SIZE = 256;

    /**
     * Redis 条件保存脚本。
     */
    private static final byte[] SAVE_IF_VERSION_SCRIPT = """
            local types = {'string', 'string', 'set', 'list'}
            for i = 1, #KEYS do
              local actual = redis.call('TYPE', KEYS[i]).ok
              if actual ~= 'none' and actual ~= types[i] then
                return redis.error_reply('WRONGTYPE data key type mismatch')
              end
            end
            local current = redis.call('GET', KEYS[2])
            if ARGV[6] == 'snapshot' then
              if current and (#current > #ARGV[2] or (#current == #ARGV[2] and current > ARGV[2])) then
                return 0
              end
            elseif ARGV[1] == '0' then
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
            redis.call('RPUSH', KEYS[4], ARGV[5])
            redis.call('LTRIM', KEYS[4], -1024, -1)
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
            redis.call('LTRIM', KEYS[4], -1024, -1)
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
     * Redis 已确认写入的本地镜像日志；不是提交前 WAL，不覆盖跨系统双写崩溃窗口。
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
        this.namespace = requireRedisSegment(namespace, "namespace");
        this.collection = requireRedisSegment(collection, "collection");
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
     * 分桶批量查询全部信封；跨桶弱一致，最多扫描 100000 个索引或 ID。
     *
     * @return 信封列表；不可为空；可能为空；线程安全性由 Jedis 保证。
     */
    @Override
    public List<ZeroDataEnvelope> findAll() {
        return findByIds(boundedIds());
    }

    /** 按桶分组 MGET 并 pipeline；结果不可变，保持输入顺序和重复项，省略缺失项。 */
    @Override
    public List<ZeroDataEnvelope> findByIds(final Collection<String> ids) {
        List<String> current = List.copyOf(ids);
        var found = new HashMap<String, ZeroDataEnvelope>();
        try {
            for (int offset = 0; offset < current.size(); offset += BATCH_SIZE) {
                var buckets = new LinkedHashMap<String, List<String>>();
                for (String id : current.subList(offset, Math.min(offset + BATCH_SIZE, current.size()))) {
                    buckets.computeIfAbsent(indexKey(id), ignored -> new ArrayList<>()).add(id);
                }
                try (var pipeline = client.pipelined()) {
                    List<redis.clients.jedis.Response<List<byte[]>>> replies = new ArrayList<>(buckets.size());
                    for (List<String> bucket : buckets.values()) {
                        byte[][] keys = bucket.stream().map(id -> bytes(dataKey(id))).toArray(byte[][]::new);
                        replies.add(pipeline.mget(keys));
                    }
                    pipeline.sync();
                    for (var reply : replies) {
                        for (byte[] value : reply.get()) {
                            if (value != null) {
                                ZeroDataEnvelope envelope = envelopeCodec.decode(value);
                                found.put(envelope.id(), envelope);
                            }
                        }
                    }
                }
            }
            List<ZeroDataEnvelope> result = new ArrayList<>(current.size());
            for (String id : current) {
                if (found.containsKey(id)) result.add(found.get(id));
            }
            return List.copyOf(result);
        } catch (RuntimeException failure) {
            if (failure instanceof ZeroException zero) throw zero;
            throw ZeroException.of(DataErrorCode.READ_FAILED, "redis batch read failed", failure);
        }
    }

    /**
     * 按 ID 排序的弱一致分页；有界扫描索引元数据，仅读取当前页 payload。
     * 索引扫描沿用 100000 预算，超限明确失败；并发删除时本页可能不足 limit。
     */
    @Override
    public PageResult<ZeroDataEnvelope> findPage(final PageRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> ids = boundedIds();
        int from = Math.min(request.offset(), ids.size());
        int to = (int) Math.min((long) ids.size(), (long) request.offset() + request.limit());
        return new PageResult<>(findByIds(ids.subList(from, to)), to < ids.size() ? String.valueOf(to) : null, ids.size());
    }

    private List<String> boundedIds() {
        try {
            java.util.Set<String> results = new java.util.TreeSet<>();
            int scanned = 0;
            for (String indexKey : boundedIndexKeys()) {
                String cursor = "0";
                do {
                    var page = client.sscan(indexKey, cursor, new redis.clients.jedis.params.ScanParams().count(256));
                    cursor = page.getCursor();
                    List<String> ids = page.getResult();
                    scanned += ids.size();
                    if (scanned > MAX_READ_ENTRIES) throw readLimit();
                    results.addAll(ids);
                } while (!"0".equals(cursor));
            }
            return List.copyOf(results);
        } catch (RuntimeException ex) {
            if (ex instanceof ZeroException zero) throw zero;
            throw ZeroException.of(DataErrorCode.READ_FAILED, "redis index scan failed", ex);
        }
    }

    /** 大集合扫描的硬预算，避免无界响应和不停增长的游标遍历。 */
    private static final int MAX_READ_ENTRIES = 100_000;

    private List<String> boundedIndexKeys() {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        String cursor = "0";
        int scanned = 0;
        do {
            var page = client.sscan(collectionIndexKey(), cursor,
                    new redis.clients.jedis.params.ScanParams().count(256));
            cursor = page.getCursor();
            scanned += page.getResult().size();
            if (scanned > MAX_READ_ENTRIES) throw readLimit();
            keys.addAll(page.getResult());
        } while (!"0".equals(cursor));
        return List.copyOf(keys);
    }

    private ZeroException readLimit() {
        return ZeroException.of(DataErrorCode.READ_LIMIT_EXCEEDED, "redis scan budget exceeded", null);
    }

    /**
     * 原子保存同版或更高版本快照；支持重放和版本跳跃，禁止版本回退。
     *
     * @param envelope 信封；不可为空。
     */
    @Override
    public void save(final ZeroDataEnvelope envelope) {
        try {
            ZeroDataEnvelope current = validateEnvelope(envelope);
            boolean saved = write(current, 0L, "snapshot");
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
        return write(validateEnvelope(envelope), expectedVersion, "cas");
    }

    /** 有界 pipeline CAS；目录登记先成功，逐项结果不可变且有序，成功提交后统一本地刷盘。 */
    @Override
    public List<Boolean> saveIfVersionBatch(final Collection<ConditionalWrite> writes) {
        List<ConditionalWrite> current = List.copyOf(writes);
        for (ConditionalWrite write : current) validateEnvelope(write.envelope());
        List<Boolean> result = new ArrayList<>(current.size());
        try {
            for (int offset = 0; offset < current.size(); offset += BATCH_SIZE) {
                List<ScriptWrite> batch = new ArrayList<>();
                for (ConditionalWrite write : current.subList(offset, Math.min(offset + BATCH_SIZE, current.size()))) {
                    batch.add(prepareScriptWrite(write.envelope(), write.expectedVersion(), "cas"));
                }
                String[] buckets = batch.stream().map(ScriptWrite::indexKey).distinct().toArray(String[]::new);
                client.sadd(collectionIndexKey(), buckets);
                try (var pipeline = client.pipelined()) {
                    List<redis.clients.jedis.Response<Object>> replies = new ArrayList<>(batch.size());
                    for (ScriptWrite write : batch) replies.add(pipeline.eval(SAVE_IF_VERSION_SCRIPT, write.keys(), write.args()));
                    pipeline.sync();
                    result.addAll(finishScripts(replies, batch.stream().map(ScriptWrite::entry).toList(), false));
                }
            }
            return List.copyOf(result);
        } catch (RuntimeException failure) {
            if (failure instanceof ZeroException zero) throw zero;
            throw ZeroException.of(DataErrorCode.WRITE_FAILED, "redis batch conditional write failed", failure);
        }
    }

    private ScriptWrite prepareScriptWrite(final ZeroDataEnvelope envelope, final long expected, final String mode) {
        RedisDataSnapshot snapshot = RedisDataSnapshot.fromEnvelope(envelope, keyStrategy, envelopeCodec);
        RedisDataJournalEntry entry = RedisDataJournalEntry.put(envelope, envelopeCodec);
        return new ScriptWrite(snapshot.indexKey(),
                List.of(bytes(snapshot.dataKey()), bytes(versionKey(snapshot.id())), bytes(snapshot.indexKey()),
                        bytes(snapshot.journalKey())),
                List.of(bytes(String.valueOf(expected)), bytes(String.valueOf(envelope.version())), snapshot.envelopeBytes(),
                        bytes(snapshot.id()), journalEntryCodec.encode(entry), bytes(mode)), entry);
    }

    /** pipeline 的每项错误均检查，部分成功仍记入本地镜像，再将失败传播给调用方。 */
    private List<Boolean> finishScripts(final List<redis.clients.jedis.Response<Object>> replies,
                                       final List<RedisDataJournalEntry> entries, final boolean deleting) {
        List<Boolean> result = new ArrayList<>(replies.size());
        List<RedisDataJournalEntry> committed = new ArrayList<>();
        RuntimeException failure = null;
        for (int index = 0; index < replies.size(); index++) {
            try {
                Object value = replies.get(index).get();
                if (deleting && value instanceof Number number && number.longValue() == -1L) {
                    throw ZeroException.of(DataErrorCode.VERSION_CONFLICT, "redis batch delete version conflict", null);
                }
                if (!(value instanceof Number number) || number.longValue() < 0L || number.longValue() > 1L) {
                    throw ZeroException.of(deleting ? DataErrorCode.DELETE_FAILED : DataErrorCode.WRITE_FAILED,
                            "unexpected redis batch script result", null);
                }
                boolean success = ((Number) value).longValue() == 1L;
                result.add(success);
                if (success) committed.add(entries.get(index));
            } catch (RuntimeException ex) {
                if (failure == null) failure = ex;
                else failure.addSuppressed(ex);
            }
        }
        try {
            if (localJournal != null) localJournal.appendAll(committed);
        } catch (RuntimeException ex) {
            if (failure == null) failure = ex;
            else failure.addSuppressed(ex);
        }
        if (failure != null) throw failure;
        return List.copyOf(result);
    }

    /** 内部已编码脚本项，不暴露可变字节数组。 */
    private record ScriptWrite(String indexKey, List<byte[]> keys, List<byte[]> args, RedisDataJournalEntry entry) { }

    private boolean write(final ZeroDataEnvelope current, final long expectedVersion, final String mode) {
        RedisDataJournalEntry entry = RedisDataJournalEntry.put(current, envelopeCodec);
        try {
            RedisDataSnapshot snapshot = RedisDataSnapshot.fromEnvelope(current, keyStrategy, envelopeCodec);
            // 先登记保守桶目录；目录失败不得发生在对象 CAS 已提交之后。
            // CAS 拒绝可能留下空桶目录，扫描空桶不产生对象，也不破坏同槽原子写。
            client.sadd(collectionIndexKey(), snapshot.indexKey());
            Object result = client.eval(
                    SAVE_IF_VERSION_SCRIPT,
                    List.of(
                            bytes(snapshot.dataKey()),
                            bytes(versionKey(snapshot.id())),
                            bytes(snapshot.indexKey()),
                            bytes(snapshot.journalKey())),
                    List.of(
                            bytes(String.valueOf(expectedVersion)),
                            bytes(String.valueOf(current.version())),
                            snapshot.envelopeBytes(),
                            bytes(snapshot.id()),
                            journalEntryCodec.encode(entry), bytes(mode)));
            if (!(result instanceof Number number) || (number.longValue() != 0L && number.longValue() != 1L)) {
                throw ZeroException.of(DataErrorCode.WRITE_FAILED, "unexpected redis save result", null);
            }
            boolean saved = ((Number) result).longValue() == 1L;
            if (saved) appendLocal(entry);
            return saved;
        } catch (RuntimeException ex) {
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
            if (((Number) result).longValue() == 1L) appendLocal(entry);
        } catch (RuntimeException ex) {
            if (ex instanceof ZeroException zeroException) {
                throw zeroException;
            }
            throw ZeroException.of(DataErrorCode.DELETE_FAILED, "redis delete failed", ex);
        }
    }

    /** 批量读当前版本后 pipeline 条件删除；不删除读后被更新的对象，成功项统一本地刷盘。 */
    @Override
    public void deleteAll(final Collection<String> ids) {
        List<String> current = List.copyOf(ids);
        try {
            for (int offset = 0; offset < current.size(); offset += BATCH_SIZE) {
                List<ZeroDataEnvelope> envelopes = findByIds(current.subList(offset, Math.min(offset + BATCH_SIZE, current.size())));
                List<RedisDataJournalEntry> entries = new ArrayList<>(envelopes.size());
                try (var pipeline = client.pipelined()) {
                    List<redis.clients.jedis.Response<Object>> replies = new ArrayList<>(envelopes.size());
                    for (ZeroDataEnvelope envelope : envelopes) {
                        String id = envelope.id();
                        RedisDataJournalEntry entry = RedisDataJournalEntry.delete(namespace, collection, id,
                                envelope.version(), System.currentTimeMillis());
                        entries.add(entry);
                        replies.add(pipeline.eval(DELETE_SCRIPT,
                                List.of(bytes(dataKey(id)), bytes(versionKey(id)), bytes(indexKey(id)), bytes(journalKey(id))),
                                List.of(bytes(id), journalEntryCodec.encode(entry), bytes(String.valueOf(envelope.version())))));
                    }
                    pipeline.sync();
                    finishScripts(replies, entries, true);
                }
            }
        } catch (RuntimeException failure) {
            if (failure instanceof ZeroException zero) throw zero;
            throw ZeroException.of(DataErrorCode.DELETE_FAILED, "redis batch delete failed", failure);
        }
    }

    /**
     * 批量统计各桶数量；并发写期间不承诺跨桶同一时刻快照。
     *
     * @return 信封数量；线程安全性由 Jedis 保证。
     */
    @Override
    public long count() {
        try {
            List<String> keys = boundedIndexKeys();
            long total = 0L;
            for (int offset = 0; offset < keys.size(); offset += 256) {
                try (var pipeline = client.pipelined()) {
                    List<redis.clients.jedis.Response<Long>> counts = new ArrayList<>();
                    for (String key : keys.subList(offset, Math.min(keys.size(), offset + 256))) {
                        counts.add(pipeline.scard(key));
                    }
                    pipeline.sync();
                    for (var count : counts) total += count.get();
                }
            }
            return total;
        } catch (RuntimeException ex) {
            if (ex instanceof ZeroException zero) throw zero;
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

    private String requireRedisSegment(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (current.indexOf('{') >= 0 || current.indexOf('}') >= 0) {
            throw new IllegalArgumentException(name + " must not contain redis hash tag braces");
        }
        if (current.indexOf(':') >= 0) {
            throw new IllegalArgumentException(name + " must not contain redis slot separator ':'");
        }
        return current;
    }
}
