package group.zn.zero.rpc.codec;

import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.codec.ProtocolCodec;
import java.util.Objects;

/**
 * RPC 类型与协议 codec 绑定。
 *
 * @param messageType 消息类型。
 * @param definition 协议定义。
 * @param codec 协议 codec。
 * @param <T> 消息类型。
 * @author zn
 */
public record RpcCodecBinding<T>(
        Class<T> messageType,
        ProtocolDefinition definition,
        ProtocolCodec<T> codec) {

    /**
     * 创建 RPC 类型与协议 codec 绑定。
     *
     * @throws NullPointerException 当消息类型、协议定义或 codec 为空时抛出。
     */
    public RpcCodecBinding {
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(codec, "codec");
    }

    /**
     * 编码消息。
     *
     * @param message 消息；不可为空。
     * @return payload 字节副本；有序、可能为空、可变、线程安全。
     */
    public byte[] encode(final T message) {
        return codec.encode(definition, message);
    }

    /**
     * 解码消息。
     *
     * @param payload payload 字节；不可为空。
     * @return 消息；不可为空；线程安全性由 codec 决定。
     */
    public T decode(final byte[] payload) {
        return codec.decode(definition, payload, messageType);
    }
}
