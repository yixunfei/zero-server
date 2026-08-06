package group.zn.zero.codegen.dsl;

import group.zn.zero.codegen.model.ProtocolEnum;
import group.zn.zero.codegen.model.ProtocolMessage;
import group.zn.zero.codegen.model.ProtocolMethod;
import group.zn.zero.protocol.ProtocolDefinition;
import java.util.List;
import java.util.Objects;

/**
 * 协议 DSL 文档。
 *
 * @param namespace 命名空间。
 * @param protocols 协议定义列表。
 * @param messages 协议消息列表。
 * @param methods 协议方法列表。
 * @author zn
 */
public record ProtocolDslDocument(
        String namespace,
        List<ProtocolDefinition> protocols,
        List<ProtocolEnum> enums,
        List<ProtocolMessage> messages,
        List<ProtocolMethod> methods) {

    /**
     * 创建兼容旧入口的协议 DSL 文档。
     *
     * @param namespace 命名空间。
     * @param protocols 协议定义列表。
     */
    public ProtocolDslDocument(final String namespace, final List<ProtocolDefinition> protocols) {
        this(namespace, protocols, List.of(), List.of(), List.of());
    }

    /**
     * 创建兼容旧入口的协议 DSL 文档。
     *
     * @param namespace 命名空间。
     * @param protocols 协议定义列表。
     * @param messages 协议消息列表。
     * @param methods 协议方法列表。
     */
    public ProtocolDslDocument(
            final String namespace,
            final List<ProtocolDefinition> protocols,
            final List<ProtocolMessage> messages,
            final List<ProtocolMethod> methods) {
        this(namespace, protocols, List.of(), messages, methods);
    }

    /**
     * 创建协议 DSL 文档。
     *
     * @throws NullPointerException 当命名空间、协议列表、消息列表或方法列表为空时抛出。
     */
    public ProtocolDslDocument {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(protocols, "protocols");
        Objects.requireNonNull(enums, "enums");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(methods, "methods");
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        protocols = List.copyOf(protocols);
        enums = List.copyOf(enums);
        messages = List.copyOf(messages);
        methods = List.copyOf(methods);
    }

    /**
     * 返回协议定义列表。
     *
     * @return 不可变、有序、可能为空、线程安全的协议定义列表。
     */
    @Override
    public List<ProtocolDefinition> protocols() {
        return protocols;
    }

    /**
     * 返回协议枚举定义列表。
     *
     * @return 不可变、有序、可能为空、线程安全的协议枚举定义列表。
     */
    @Override
    public List<ProtocolEnum> enums() {
        return enums;
    }

    /**
     * 返回协议消息列表。
     *
     * @return 不可变、有序、可能为空、线程安全的协议消息列表。
     */
    @Override
    public List<ProtocolMessage> messages() {
        return messages;
    }

    /**
     * 返回协议方法列表。
     *
     * @return 不可变、有序、可能为空、线程安全的协议方法列表。
     */
    @Override
    public List<ProtocolMethod> methods() {
        return methods;
    }
}
