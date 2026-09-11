package group.zn.zero.data.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.UpdateResult;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore;
import group.zn.zero.data.error.DataErrorCode;
import java.util.ArrayList;
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
        this.namespace = requireText(namespace, "namespace");
        this.collectionName = requireText(collectionName, "collectionName");
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
        try (MongoCursor<Document> cursor = mongoCollection.find(matchCollection()).iterator()) {
            List<ZeroDataEnvelope> results = new ArrayList<>();
            while (cursor.hasNext()) {
                results.add(MongoDataDocument.fromBson(cursor.next()).toEnvelope());
            }
            return List.copyOf(results);
        } catch (RuntimeException ex) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "mongo read all failed", ex);
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
            MongoDataDocument document = MongoDataDocument.fromEnvelope(validateEnvelope(envelope));
            mongoCollection.replaceOne(matchId(document.id()), document.toBson(), new ReplaceOptions().upsert(true));
        } catch (RuntimeException ex) {
            throw ZeroException.of(DataErrorCode.WRITE_FAILED, "mongo write failed", ex);
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
            throw ZeroException.of(DataErrorCode.WRITE_FAILED, "mongo conditional write failed", ex);
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
}
