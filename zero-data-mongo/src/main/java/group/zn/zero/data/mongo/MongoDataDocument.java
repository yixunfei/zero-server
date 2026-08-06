package group.zn.zero.data.mongo;

import group.zn.zero.data.envelope.ZeroDataEnvelope;
import java.util.Arrays;
import java.util.Objects;
import org.bson.Document;
import org.bson.types.Binary;

/**
 * MongoDB 通用数据 document。
 *
 * <p>首版不展开业务字段，只保存映射元数据和 zcode payload。后续接入官方 MongoDB
 * Java driver 时，该结构对应单条 BSON document 的稳定字段。
 *
 * @param id MongoDB `_id`，使用统一 key codec 编码。
 * @param namespace 数据命名空间。
 * @param collection 数据集合名称。
 * @param version 对象版本号。
 * @param schemaVersion schema 版本。
 * @param codecVersion payload codec 版本。
 * @param updatedAtEpochMillis 更新时间，Unix epoch 毫秒。
 * @param payload 业务对象 zcode payload 字节。
 * @author zn
 */
public record MongoDataDocument(
        String id,
        String namespace,
        String collection,
        long version,
        int schemaVersion,
        int codecVersion,
        long updatedAtEpochMillis,
        byte[] payload) {

    /**
     * 创建 MongoDB 通用数据 document。
     *
     * @throws NullPointerException 当必要字段为空时抛出。
     */
    public MongoDataDocument {
        id = Objects.requireNonNull(id, "id");
        namespace = Objects.requireNonNull(namespace, "namespace");
        collection = Objects.requireNonNull(collection, "collection");
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
    }

    /**
     * 基于统一数据对象信封创建 document。
     *
     * @param envelope 数据对象信封；不可为空。
     * @return MongoDB 通用数据 document；不可为空；线程安全。
     */
    public static MongoDataDocument fromEnvelope(final ZeroDataEnvelope envelope) {
        ZeroDataEnvelope current = Objects.requireNonNull(envelope, "envelope");
        return new MongoDataDocument(
                current.id(),
                current.namespace(),
                current.collection(),
                current.version(),
                current.schemaVersion(),
                current.codecVersion(),
                current.encodedAtEpochMillis(),
                current.payload());
    }

    /**
     * 基于 BSON document 创建通用数据 document。
     *
     * @param document BSON document；不可为空。
     * @return MongoDB 通用数据 document；不可为空；线程安全。
     */
    public static MongoDataDocument fromBson(final Document document) {
        Document current = Objects.requireNonNull(document, "document");
        Binary payloadBinary = current.get("payload", Binary.class);
        return new MongoDataDocument(
                current.getString("_id"),
                current.getString("namespace"),
                current.getString("collection"),
                number(current, "version").longValue(),
                number(current, "schemaVersion").intValue(),
                number(current, "codecVersion").intValue(),
                number(current, "updatedAtEpochMillis").longValue(),
                payloadBinary.getData());
    }

    /**
     * 转换为统一数据对象信封。
     *
     * @return 数据对象信封；不可为空；线程安全。
     */
    public ZeroDataEnvelope toEnvelope() {
        return new ZeroDataEnvelope(
                namespace,
                collection,
                id,
                version,
                schemaVersion,
                codecVersion,
                updatedAtEpochMillis,
                payload);
    }

    /**
     * 转换为 BSON document。
     *
     * @return BSON document；不可为空；线程安全。
     */
    public Document toBson() {
        return new Document("_id", id)
                .append("namespace", namespace)
                .append("collection", collection)
                .append("version", version)
                .append("schemaVersion", schemaVersion)
                .append("codecVersion", codecVersion)
                .append("updatedAtEpochMillis", updatedAtEpochMillis)
                .append("payload", new Binary(payload));
    }

    /**
     * 返回 payload 副本。
     *
     * @return payload 副本；不可为空；有序；可能为空；线程安全。
     */
    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    private static Number number(final Document document, final String name) {
        return Objects.requireNonNull(document.get(name, Number.class), name);
    }
}
