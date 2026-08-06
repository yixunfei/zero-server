package group.zn.zero.codegen.model;

import java.util.List;
import java.util.Objects;

/**
 * 协议字段类型。
 *
 * @param kind 类型种类。
 * @param name 类型名称。
 * @param arguments 泛型参数。
 * @author zn
 */
public record ProtocolType(ProtocolTypeKind kind, String name, List<ProtocolType> arguments) {

    /**
     * 创建协议字段类型。
     *
     * @throws NullPointerException 当类型种类、名称或参数列表为空时抛出。
     */
    public ProtocolType {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        if (name.isBlank() && kind != ProtocolTypeKind.NULL) {
            throw new IllegalArgumentException("type name must not be blank");
        }
        arguments = List.copyOf(arguments);
    }

    /**
     * 创建标量类型。
     *
     * @param name 类型名称；不可为空。
     * @return 标量类型；不可为空；线程安全。
     */
    public static ProtocolType scalar(final String name) {
        return new ProtocolType(ProtocolTypeKind.SCALAR, name, List.of());
    }

    /**
     * 创建消息引用类型。
     *
     * @param name 消息名称；不可为空。
     * @return 消息引用类型；不可为空；线程安全。
     */
    public static ProtocolType message(final String name) {
        return new ProtocolType(ProtocolTypeKind.MESSAGE, name, List.of());
    }

    /**
     * 创建枚举引用类型。
     *
     * @param name 枚举名称；不可为空。
     * @return 枚举引用类型；不可为空；线程安全。
     */
    public static ProtocolType enumType(final String name) {
        return new ProtocolType(ProtocolTypeKind.ENUM, name, List.of());
    }

    /**
     * 创建列表类型。
     *
     * @param element 元素类型；不可为空。
     * @return 列表类型；不可为空；线程安全。
     */
    public static ProtocolType list(final ProtocolType element) {
        return new ProtocolType(ProtocolTypeKind.LIST, "List", List.of(element));
    }

    /**
     * 创建集合类型。
     *
     * @param element 元素类型；不可为空。
     * @return 集合类型；不可为空；线程安全。
     */
    public static ProtocolType set(final ProtocolType element) {
        return new ProtocolType(ProtocolTypeKind.SET, "Set", List.of(element));
    }

    /**
     * 创建数组类型。
     *
     * @param element 元素类型；不可为空。
     * @return 数组类型；不可为空；线程安全。
     */
    public static ProtocolType array(final ProtocolType element) {
        return new ProtocolType(ProtocolTypeKind.ARRAY, "Array", List.of(element));
    }

    /**
     * 创建映射类型。
     *
     * @param key key 类型；不可为空。
     * @param value value 类型；不可为空。
     * @return 映射类型；不可为空；线程安全。
     */
    public static ProtocolType map(final ProtocolType key, final ProtocolType value) {
        return new ProtocolType(ProtocolTypeKind.MAP, "Map", List.of(key, value));
    }

    /**
     * 创建可选类型。
     *
     * @param value 值类型；不可为空。
     * @return 可选类型；不可为空；线程安全。
     */
    public static ProtocolType optional(final ProtocolType value) {
        return new ProtocolType(ProtocolTypeKind.OPTIONAL, "Optional", List.of(value));
    }

    /**
     * 创建 null 类型标记。
     *
     * @return null 类型标记；不可为空；线程安全。
     */
    public static ProtocolType nullType() {
        return new ProtocolType(ProtocolTypeKind.NULL, "", List.of());
    }

    /**
     * 返回泛型参数。
     *
     * @return 不可变、有序、可能为空、线程安全的泛型参数。
     */
    @Override
    public List<ProtocolType> arguments() {
        return arguments;
    }
}
