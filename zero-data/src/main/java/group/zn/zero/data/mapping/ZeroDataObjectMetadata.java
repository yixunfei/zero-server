package group.zn.zero.data.mapping;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 数据对象元数据。
 *
 * @param objectType 对象类型。
 * @param namespace 命名空间。
 * @param collection 集合名称。
 * @param schemaVersion schema 版本。
 * @param keyPrefix key 前缀。
 * @param compositeKey 是否组合键。
 * @param keyCodecType key 编码器类型。
 * @param idGeneratorType ID 生成器类型。
 * @param fields 持久化字段。
 * @param keyParts 组合键字段。
 * @param idField ID 字段。
 * @param versionField 版本字段。
 * @author zn
 */
public record ZeroDataObjectMetadata(
        Class<?> objectType,
        String namespace,
        String collection,
        int schemaVersion,
        String keyPrefix,
        boolean compositeKey,
        Class<? extends ZeroDataKeyCodec<?>> keyCodecType,
        Class<? extends ZeroDataKeyGenerator<?>> idGeneratorType,
        List<ZeroDataFieldMetadata> fields,
        List<ZeroDataFieldMetadata> keyParts,
        Optional<ZeroDataFieldMetadata> idField,
        Optional<ZeroDataFieldMetadata> versionField) {

    /**
     * 创建数据对象元数据。
     *
     * @throws NullPointerException 当必要参数为空时抛出。
     */
    public ZeroDataObjectMetadata {
        Objects.requireNonNull(objectType, "objectType");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        Objects.requireNonNull(keyCodecType, "keyCodecType");
        Objects.requireNonNull(idGeneratorType, "idGeneratorType");
        fields = sortedCopy(Objects.requireNonNull(fields, "fields"));
        keyParts = sortedCopy(Objects.requireNonNull(keyParts, "keyParts"));
        idField = Objects.requireNonNull(idField, "idField");
        versionField = Objects.requireNonNull(versionField, "versionField");
    }

    private static List<ZeroDataFieldMetadata> sortedCopy(final List<ZeroDataFieldMetadata> values) {
        return values.stream()
                .sorted(Comparator.comparingInt(ZeroDataFieldMetadata::order)
                        .thenComparing(ZeroDataFieldMetadata::javaName))
                .toList();
    }
}
