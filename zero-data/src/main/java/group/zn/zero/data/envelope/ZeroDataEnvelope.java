package group.zn.zero.data.envelope;

import java.util.Arrays;
import java.util.Objects;

/**
 * 数据对象 zcode 存储信封。
 *
 * <p>信封承载存储元数据和业务对象 payload。MongoDB、Redis、本地磁盘和 PostgreSQL
 * 应复用该结构，避免各自维护不一致的对象格式。
 *
 * @param namespace 数据命名空间。
 * @param collection 数据集合名称。
 * @param id 已编码的存储 ID。
 * @param version 对象版本号。
 * @param schemaVersion 映射 schema 版本。
 * @param codecVersion payload codec 版本。
 * @param encodedAtEpochMillis 编码时间，Unix epoch 毫秒。
 * @param payload 业务对象 zcode payload 字节。
 * @author zn
 */
public record ZeroDataEnvelope(
        String namespace,
        String collection,
        String id,
        long version,
        int schemaVersion,
        int codecVersion,
        long encodedAtEpochMillis,
        byte[] payload) {

    /**
     * 创建数据对象 zcode 存储信封。
     *
     * @throws NullPointerException 当必要字段为空时抛出。
     * @throws IllegalArgumentException 当版本、schema 或 codec 版本非法时抛出。
     */
    public ZeroDataEnvelope {
        namespace = requireText(namespace, "namespace");
        collection = requireText(collection, "collection");
        id = requireText(id, "id");
        if (version <= 0L) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (codecVersion <= 0) {
            throw new IllegalArgumentException("codecVersion must be positive");
        }
        if (encodedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("encodedAtEpochMillis must be non-negative");
        }
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
    }

    /**
     * 返回业务对象 payload 副本。
     *
     * @return payload 副本；不可为空；有序；可能为空；线程安全。
     */
    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
