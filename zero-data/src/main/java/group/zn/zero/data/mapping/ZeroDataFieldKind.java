package group.zn.zero.data.mapping;

/**
 * 数据字段类型。
 *
 * @author zn
 */
public enum ZeroDataFieldKind {

    /**
     * 普通数据字段。
     */
    DATA,

    /**
     * 对象 ID 字段。
     */
    ID,

    /**
     * 版本字段。
     */
    VERSION,

    /**
     * 组合键字段。
     */
    KEY_PART,

    /**
     * 外部对象引用。
     */
    REFERENCE,

    /**
     * 外部对象引用列表。
     */
    REFERENCE_LIST,

    /**
     * 父对象拥有的子集合。
     */
    OWNED_COLLECTION,

    /**
     * 嵌入值对象。
     */
    EMBEDDED
}
