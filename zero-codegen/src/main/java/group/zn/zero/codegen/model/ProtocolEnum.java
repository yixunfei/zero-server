package group.zn.zero.codegen.model;

import java.util.List;
import java.util.Objects;

/**
 * 协议枚举定义。
 *
 * @param name 枚举名称。
 * @param values 枚举值列表，必须保持 DSL 声明顺序。
 * @param comment 枚举注释。
 * @author zn
 */
public record ProtocolEnum(String name, List<ProtocolEnumValue> values, String comment) {

    /**
     * 创建协议枚举定义。
     *
     * @throws NullPointerException 当枚举名称或枚举值列表为空时抛出。
     * @throws IllegalArgumentException 当枚举名称为空白或枚举值列表为空时抛出。
     */
    public ProtocolEnum {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(values, "values");
        comment = comment == null ? "" : comment;
        if (name.isBlank()) {
            throw new IllegalArgumentException("enum name must not be blank");
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("enum values must not be empty");
        }
        values = List.copyOf(values);
    }

    /**
     * 返回枚举值列表。
     *
     * @return 不可变、有序、不为空、线程安全的枚举值列表。
     */
    @Override
    public List<ProtocolEnumValue> values() {
        return values;
    }
}
