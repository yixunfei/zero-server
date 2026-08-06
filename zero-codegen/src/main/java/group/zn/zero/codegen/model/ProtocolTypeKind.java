package group.zn.zero.codegen.model;

/**
 * 协议字段类型种类。
 *
 * @author zn
 */
public enum ProtocolTypeKind {

    /**
     * 标量类型。
     */
    SCALAR,

    /**
     * 枚举类型。
     */
    ENUM,

    /**
     * 消息类型。
     */
    MESSAGE,

    /**
     * 列表类型。
     */
    LIST,

    /**
     * 集合类型。
     */
    SET,

    /**
     * 数组类型。
     */
    ARRAY,

    /**
     * 映射类型。
     */
    MAP,

    /**
     * 可选类型。
     */
    OPTIONAL,

    /**
     * null 类型标记。
     */
    NULL
}
