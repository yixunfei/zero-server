package group.zn.zero.codegen.model;

import java.util.Objects;

/**
 * 协议枚举值定义。
 *
 * @param name 枚举值名称。
 * @param value 枚举线格式值，必须非负。
 * @param comment 枚举值注释。
 * @author zn
 */
public record ProtocolEnumValue(String name, int value, String comment) {

    /**
     * 创建协议枚举值定义。
     *
     * @throws NullPointerException 当枚举值名称为空时抛出。
     * @throws IllegalArgumentException 当枚举值名称为空白或枚举值为负数时抛出。
     */
    public ProtocolEnumValue {
        Objects.requireNonNull(name, "name");
        comment = comment == null ? "" : comment;
        if (name.isBlank()) {
            throw new IllegalArgumentException("enum value name must not be blank");
        }
        if (value < 0) {
            throw new IllegalArgumentException("enum value must not be negative");
        }
    }
}
