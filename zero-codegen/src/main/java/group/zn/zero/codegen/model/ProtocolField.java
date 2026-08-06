package group.zn.zero.codegen.model;

import java.util.Objects;

/**
 * 协议字段定义。
 *
 * @param name 字段名称。
 * @param type 字段类型。
 * @param order 字段顺序，从 1 开始。
 * @param nullable 是否允许为空。
 * @param compatible 是否为兼容追加字段。
 * @param comment 字段注释。
 * @author zn
 */
public record ProtocolField(
        String name,
        ProtocolType type,
        int order,
        boolean nullable,
        boolean compatible,
        String comment) {

    /**
     * 创建协议字段定义。
     *
     * @throws NullPointerException 当字段名称、类型或注释为空时抛出。
     * @throws IllegalArgumentException 当字段顺序非法时抛出。
     */
    public ProtocolField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        comment = comment == null ? "" : comment;
        if (name.isBlank()) {
            throw new IllegalArgumentException("field name must not be blank");
        }
        if (order <= 0) {
            throw new IllegalArgumentException("field order must be positive");
        }
    }
}
