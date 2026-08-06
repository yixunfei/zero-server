package group.zn.zero.data.mapping;

import java.util.Objects;
import java.util.Optional;

/**
 * 数据字段元数据。
 *
 * @param javaName Java 成员名。
 * @param storageName 存储字段名。
 * @param order 字段顺序。
 * @param fieldType 字段类型。
 * @param kind 字段类型。
 * @param targetType 引用或拥有集合目标类型。
 * @param collection 引用或拥有集合名称。
 * @param nullable 是否允许为空。
 * @author zn
 */
public record ZeroDataFieldMetadata(
        String javaName,
        String storageName,
        int order,
        Class<?> fieldType,
        ZeroDataFieldKind kind,
        Optional<Class<?>> targetType,
        Optional<String> collection,
        boolean nullable) {

    /**
     * 创建数据字段元数据。
     *
     * @throws NullPointerException 当必要参数为空时抛出。
     */
    public ZeroDataFieldMetadata {
        Objects.requireNonNull(javaName, "javaName");
        Objects.requireNonNull(storageName, "storageName");
        Objects.requireNonNull(fieldType, "fieldType");
        Objects.requireNonNull(kind, "kind");
        targetType = Objects.requireNonNull(targetType, "targetType");
        collection = Objects.requireNonNull(collection, "collection");
    }
}
