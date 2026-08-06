package group.zn.zero.data.postgresql;

import group.zn.zero.data.envelope.ZeroDataEnvelope;
import java.util.Arrays;
import java.util.Objects;

/**
 * PostgreSQL 通用对象表数据行。
 *
 * <p>首版使用通用对象表承载 zcode payload，正式账号、订单、渠道等平台表结构后续单独设计。
 *
 * @param namespace 数据命名空间。
 * @param collection 数据集合名称。
 * @param id 编码后的对象 ID。
 * @param version 对象版本号。
 * @param schemaVersion schema 版本。
 * @param codecVersion payload codec 版本。
 * @param updatedAtEpochMillis 更新时间，Unix epoch 毫秒。
 * @param payload 业务对象 zcode payload 字节。
 * @author zn
 */
public record PostgresqlDataRow(
        String namespace,
        String collection,
        String id,
        long version,
        int schemaVersion,
        int codecVersion,
        long updatedAtEpochMillis,
        byte[] payload) {

    /**
     * 创建 PostgreSQL 通用对象表数据行。
     *
     * @throws NullPointerException 当必要字段为空时抛出。
     */
    public PostgresqlDataRow {
        namespace = Objects.requireNonNull(namespace, "namespace");
        collection = Objects.requireNonNull(collection, "collection");
        id = Objects.requireNonNull(id, "id");
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
    }

    /**
     * 基于统一数据对象信封创建数据行。
     *
     * @param envelope 数据对象信封；不可为空。
     * @return PostgreSQL 通用对象表数据行；不可为空；线程安全。
     */
    public static PostgresqlDataRow fromEnvelope(final ZeroDataEnvelope envelope) {
        ZeroDataEnvelope current = Objects.requireNonNull(envelope, "envelope");
        return new PostgresqlDataRow(
                current.namespace(),
                current.collection(),
                current.id(),
                current.version(),
                current.schemaVersion(),
                current.codecVersion(),
                current.encodedAtEpochMillis(),
                current.payload());
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
     * 返回 payload 副本。
     *
     * @return payload 副本；不可为空；有序；可能为空；线程安全。
     */
    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
