package group.zn.zero.codegen.model;

import java.util.List;
import java.util.Objects;

/**
 * 协议消息定义。
 *
 * @param name 消息名称。
 * @param fields 字段列表，必须保持 DSL 声明顺序。
 * @param comment 消息注释。
 * @author zn
 */
public record ProtocolMessage(String name, List<ProtocolField> fields, String comment) {

    /**
     * 创建协议消息定义。
     *
     * @throws NullPointerException 当消息名称或字段列表为空时抛出。
     * @throws IllegalArgumentException 当消息名称为空白时抛出。
     */
    public ProtocolMessage {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(fields, "fields");
        comment = comment == null ? "" : comment;
        if (name.isBlank()) {
            throw new IllegalArgumentException("message name must not be blank");
        }
        fields = List.copyOf(fields);
    }

    /**
     * 返回字段列表。
     *
     * @return 不可变、有序、可能为空、线程安全的字段列表。
     */
    @Override
    public List<ProtocolField> fields() {
        return fields;
    }
}
