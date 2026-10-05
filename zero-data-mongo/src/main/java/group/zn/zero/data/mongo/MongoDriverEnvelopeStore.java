package group.zn.zero.data.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.in;

import com.mongodb.MongoWriteException;
import com.mongodb.MongoException;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.result.UpdateResult;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.model.PageRequest;
import group.zn.zero.data.model.PageResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;

/**
 * MongoDB driver-backed 信封存储。
 *
 * @author zn
 */
public final class MongoDriverEnvelopeStore implements ZeroDataEnvelopeStore {

    /** 限制每次 bulk/query 的参数和驱动缓存。 */
    private static final int BATCH_SIZE = 256;

    /**
     * 数据命名空间。
     */
    private final String namespace;

    /**
     * 数据集合名称。
     */
    private final String collectionName;

    /**
     * MongoDB collection。
     */
    private final MongoCollection<Document> mongoCollection;
    /** 单次全量读取上限，避免把大集合无界载入内存。 */
    private final int maxFindAll;

    /**
     * 创建 MongoDB driver-backed 信封存储。
     *
     * @param database MongoDB database；不可为空。
     * @param namespace 数据命名空间；不可为空。
     * @param collectionName 数据集合名称；不可为空。
     * @throws NullPointerException 当必要参数为空时抛出。
     */
    public MongoDriverEnvelopeStore(
            final MongoDatabase database,
            final String namespace,
            final String collectionName) {
        this(database, namespace, collectionName, 10_000);
    }

    /** 创建带有界全量读取的 Mongo 存储。 */
    public MongoDriverEnvelopeStore(final MongoDatabase database, final String namespace,
            final String collectionName, final int maxFindAll) {
        this.namespace = requireText(namespace, "namespace");
        this.collectionName = requireText(collectionName, "collectionName");
        if (maxFindAll <= 0) {
            throw new IllegalArgumentException("maxFindAll must be positive");
        }
        this.maxFindAll = maxFindAll;
        MongoDatabase checkedDatabase = Objects.requireNonNull(database, "database");
        String physicalName = physicalCollectionName(this.namespace, this.collectionName);
        // Use the sharded namespace limit so a later sharding change remains possible.
        if (checkedDatabase.getName().getBytes(StandardCharsets.UTF_8).length + 1 + physicalName.length() > 235) {
            throw new IllegalArgumentException("encoded MongoDB namespace exceeds 235 bytes");
        }
        this.mongoCollection = checkedDatabase.getCollection(physicalName);
    }

    /**
     * 根据编码 ID 查询信封。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @return 查询结果；不可为空；可能为空；线程安全性由 MongoDB driver 保证。
     */
    @Override
    public Optional<ZeroDataEnvelope> findById(final String id) {
        try {
            Document document = mongoCollection.find(matchId(Objects.requireNonNull(id, "id"))).first();
            return document == null ? Optional.empty() : Optional.of(MongoDataDocument.fromBson(document).toEnvelope());
        } catch (RuntimeException ex) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "mongo read failed", ex);
        }
    }

    /**
     * 查询全部信封。
     *
     * @return 信封列表；不可为空；可能为空；线程安全性由 MongoDB driver 保证。
     */
    @Override
    public List<ZeroDataEnvelope> findAll() {
        try (MongoCursor<Document> cursor = mongoCollection.find(matchCollection()).limit(maxFindAll + 1).iterator()) {
            List<ZeroDataEnvelope> results = new ArrayList<>(Math.min(maxFindAll, 1024));
            while (cursor.hasNext()) {
                if (results.size() >= maxFindAll) {
                    throw ZeroException.of(DataErrorCode.READ_LIMIT_EXCEEDED,
                            "mongo read all exceeds configured limit", null);
                }
                results.add(MongoDataDocument.fromBson(cursor.next()).toEnvelope());
            }
            return List.copyOf(results);
        } catch (RuntimeException ex) {
            if (ex instanceof ZeroException zeroException) {
                throw zeroException;
            }
            throw ZeroException.of(DataErrorCode.READ_FAILED, "mongo read all failed", ex);
        }
    }

    /** $in 批量查询；不可变结果按输入顺序返回，缺失项省略，重复项保留。 */
    @Override
    public List<ZeroDataEnvelope> findByIds(final Collection<String> ids) {
        List<String> current = List.copyOf(ids);
        var found = new HashMap<String, ZeroDataEnvelope>();
        try {
            for (int offset = 0; offset < current.size(); offset += BATCH_SIZE) {
                var query = and(matchCollection(), in("_id",
                        current.subList(offset, Math.min(offset + BATCH_SIZE, current.size()))));
                try (MongoCursor<Document> cursor = mongoCollection.find(query).iterator()) {
                    while (cursor.hasNext()) {
                        ZeroDataEnvelope envelope = MongoDataDocument.fromBson(cursor.next()).toEnvelope();
                        found.put(envelope.id(), envelope);
                    }
                }
            }
            List<ZeroDataEnvelope> result = new ArrayList<>(current.size());
            for (String id : current) {
                if (found.containsKey(id)) result.add(found.get(id));
            }
            return List.copyOf(result);
        } catch (RuntimeException failure) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "mongo batch read failed", failure);
        }
    }

    /** 服务端 skip/limit 分页；按 _id 排序，计数和 items 在并发写期间弱一致。 */
    @Override
    public PageResult<ZeroDataEnvelope> findPage(final PageRequest request) {
        Objects.requireNonNull(request, "request");
        try (MongoCursor<Document> cursor = mongoCollection.find(matchCollection()).sort(Sorts.ascending("_id"))
                .skip(request.offset()).limit(request.limit()).iterator()) {
            List<ZeroDataEnvelope> items = new ArrayList<>();
            while (cursor.hasNext()) items.add(MongoDataDocument.fromBson(cursor.next()).toEnvelope());
            long total = count();
            long next = (long) request.offset() + items.size();
            return new PageResult<>(items, !items.isEmpty() && next < total ? String.valueOf(next) : null, total);
        } catch (RuntimeException failure) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "mongo page read failed", failure);
        }
    }

    /**
     * 有界 bulk CAS；成功返回按输入顺序的不可变 true 列表。
     * 任一冲突抛 VERSION_CONFLICT；Mongo 的聚合结果不能定位未匹配更新，不虚构逐项结果。
     * 批次不是事务，冲突或驱动错误可能已有部分写入；调用方应重新读取后重试。
     */
    @Override
    public List<Boolean> saveIfVersionBatch(final Collection<ConditionalWrite> writes) {
        List<ConditionalWrite> current = List.copyOf(writes);
        for (ConditionalWrite write : current) validateEnvelope(write.envelope());
        try {
            for (int offset = 0; offset < current.size(); offset += BATCH_SIZE) {
                writeBatch(current.subList(offset, Math.min(offset + BATCH_SIZE, current.size())));
            }
            return List.copyOf(java.util.Collections.nCopies(current.size(), Boolean.TRUE));
        } catch (RuntimeException failure) {
            if (failure instanceof ZeroException zero) throw zero;
            throw ZeroException.of(classifyWriteFailure(failure), "mongo batch conditional write failed", failure);
        }
    }

    private void writeBatch(final List<ConditionalWrite> writes) {
        List<WriteModel<Document>> operations = new ArrayList<>(writes.size());
        int updates = 0;
        for (ConditionalWrite write : writes) {
            MongoDataDocument document = MongoDataDocument.fromEnvelope(write.envelope());
            if (write.expectedVersion() == 0L) {
                operations.add(new InsertOneModel<>(document.toBson()));
            } else {
                operations.add(new ReplaceOneModel<>(and(matchId(document.id()), eq("version", write.expectedVersion())),
                        document.toBson()));
                updates++;
            }
        }
        var result = mongoCollection.bulkWrite(operations, new BulkWriteOptions().ordered(true));
        if (result.getMatchedCount() != updates || result.getInsertedCount() != writes.size() - updates) {
            throw ZeroException.of(DataErrorCode.VERSION_CONFLICT, "mongo batch contains version conflicts; partial writes possible", null);
        }
    }

    /** $in 批量删除；有界请求，不承诺跨批次事务原子性。 */
    @Override
    public void deleteAll(final Collection<String> ids) {
        List<String> current = List.copyOf(ids);
        try {
            for (int offset = 0; offset < current.size(); offset += BATCH_SIZE) {
                mongoCollection.deleteMany(and(matchCollection(), in("_id",
                        current.subList(offset, Math.min(offset + BATCH_SIZE, current.size())))));
            }
        } catch (RuntimeException failure) {
            throw ZeroException.of(DataErrorCode.DELETE_FAILED, "mongo batch delete failed", failure);
        }
    }

    /**
     * 保存信封；不会覆盖后端更高版本。
     *
     * @param envelope 信封；不可为空。
     */
    @Override
    public void save(final ZeroDataEnvelope envelope) {
        try {
            MongoDataDocument document = MongoDataDocument.fromEnvelope(validateEnvelope(envelope));
            UpdateResult result = mongoCollection.replaceOne(
                    and(matchId(document.id()), lte("version", document.version())),
                    document.toBson(), new ReplaceOptions().upsert(true));
            if (result.getMatchedCount() == 0L && result.getUpsertedId() == null) {
                throw ZeroException.of(DataErrorCode.VERSION_CONFLICT,
                        "mongo save would move version backwards", null);
            }
        } catch (RuntimeException ex) {
            if (ex instanceof ZeroException zeroException) {
                throw zeroException;
            }
            throw ZeroException.of(classifyWriteFailure(ex), "mongo write failed", ex);
        }
    }

    /**
     * 按期望版本条件保存信封。
     *
     * @param envelope 信封；不可为空。
     * @param expectedVersion 期望当前版本；必须大于等于 0。
     * @return true 表示保存成功；false 表示版本条件不满足；线程安全性由 MongoDB driver 保证。
     */
    @Override
    public boolean saveIfVersion(final ZeroDataEnvelope envelope, final long expectedVersion) {
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        try {
            MongoDataDocument document = MongoDataDocument.fromEnvelope(validateEnvelope(envelope));
            if (expectedVersion == 0L) {
                return insertIfAbsent(document);
            }
            UpdateResult result = mongoCollection.replaceOne(
                    and(matchId(document.id()), eq("version", expectedVersion)),
                    document.toBson());
            return result.getMatchedCount() == 1L;
        } catch (RuntimeException ex) {
            throw ZeroException.of(classifyWriteFailure(ex), "mongo conditional write failed", ex);
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
            mongoCollection.deleteOne(matchId(Objects.requireNonNull(id, "id")));
        } catch (RuntimeException ex) {
            throw ZeroException.of(DataErrorCode.DELETE_FAILED, "mongo delete failed", ex);
        }
    }

    /**
     * 统计信封数量。
     *
     * @return 信封数量；线程安全性由 MongoDB driver 保证。
     */
    @Override
    public long count() {
        try {
            return mongoCollection.countDocuments(matchCollection(), new CountOptions());
        } catch (RuntimeException ex) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "mongo count failed", ex);
        }
    }

    private boolean insertIfAbsent(final MongoDataDocument document) {
        try {
            mongoCollection.insertOne(document.toBson());
            return true;
        } catch (MongoWriteException ex) {
            if (ex.getError().getCode() != 11000) {
                throw ex;
            }
            return false;
        }
    }

    private ZeroDataEnvelope validateEnvelope(final ZeroDataEnvelope envelope) {
        ZeroDataEnvelope current = Objects.requireNonNull(envelope, "envelope");
        if (!namespace.equals(current.namespace()) || !collectionName.equals(current.collection())) {
            throw ZeroException.of(DataErrorCode.MAPPING_INVALID, "mongo data envelope collection mismatch", null);
        }
        return current;
    }

    private org.bson.conversions.Bson matchId(final String id) {
        return and(matchCollection(), eq("_id", id));
    }

    private org.bson.conversions.Bson matchCollection() {
        return and(eq("namespace", namespace), eq("collection", collectionName));
    }

    private String physicalCollectionName(final String namespace, final String collectionName) {
        return "z_" + encodeMongoSegment(namespace) + "__" + encodeMongoSegment(collectionName);
    }

    private String encodeMongoSegment(final String value) {
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException("MongoDB logical name must contain valid Unicode");
        }
        return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }

    private DataErrorCode classifyWriteFailure(final RuntimeException failure) {
        if (failure instanceof MongoBulkWriteException bulk && bulk.getWriteConcernError() == null
                && !bulk.getWriteErrors().isEmpty()
                && bulk.getWriteErrors().stream().allMatch(error -> error.getCode() == 11000)) {
            return DataErrorCode.VERSION_CONFLICT;
        }
        if (failure instanceof com.mongodb.DuplicateKeyException) {
            return DataErrorCode.VERSION_CONFLICT;
        }
        if (failure instanceof MongoException mongoException && mongoException.hasErrorLabel("TransientTransactionError")) {
            return DataErrorCode.CONCURRENT_WRITE;
        }
        if (failure instanceof MongoException mongoException && mongoException.getCode() == 11000) {
            return DataErrorCode.VERSION_CONFLICT;
        }
        if (failure instanceof com.mongodb.MongoSocketException
                || failure instanceof com.mongodb.MongoTimeoutException) {
            return DataErrorCode.BACKEND_UNAVAILABLE;
        }
        return DataErrorCode.WRITE_FAILED;
    }
}
