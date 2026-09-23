package group.zn.zero.protocol.codec;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.error.ProtocolErrorCode;
import java.util.Objects;

/**
 * 基于生成式 payload codec 的协议编解码器。
 *
 * @param <T> 消息类型。
 * @author zn
 */
public final class GeneratedProtocolCodec<T> implements ProtocolCodec<T> {

    /**
     * payload codec。
     */
    private final ZeroPayloadCodec<T> payloadCodec;

    /**
     * 创建协议编解码器。
     *
     * @param payloadCodec payload codec；不可为空。
     * @throws NullPointerException 当 payload codec 为空时抛出。
     */
    public GeneratedProtocolCodec(final ZeroPayloadCodec<T> payloadCodec) {
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec");
    }

    /**
     * 返回提供者名称。
     *
     * @return 提供者名称；不可为空；线程安全。
     */
    @Override
    public String name() {
        return payloadCodec.name();
    }

    /**
     * 返回提供者优先级。
     *
     * @return 数值越小优先级越高；线程安全。
     */
    @Override
    public int priority() {
        return payloadCodec.priority();
    }

    /**
     * 编码协议消息。
     *
     * @param definition 协议定义；不可为空。
     * @param message 协议消息；不可为空。
     * @return 编码后的字节；不可为空；返回数组可变且有序；线程安全。
     */
    @Override
    public byte[] encode(final ProtocolDefinition definition, final T message) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(message, "message");
        if (!payloadCodec.messageType().isInstance(message)) {
            throw ZeroException.of(
                    ProtocolErrorCode.ENCODE_FAILED,
                    "message type does not match payload codec: " + message.getClass().getName(),
                    null);
        }
        ZeroWriter writer = EncodingWriters.acquire(payloadCodec.estimatedSize(message));
        try {
            payloadCodec.write(writer, message);
            return writer.toByteArray();
        } finally {
            EncodingWriters.release(writer);
        }
    }

    /**
     * 解码协议消息。
     *
     * @param definition 协议定义；不可为空。
     * @param payload 协议字节；不可为空。
     * @param messageType 目标消息类型；不可为空。
     * @return 解码后的协议消息；不可为空；线程安全。
     */
    @Override
    public T decode(
            final ProtocolDefinition definition,
            final byte[] payload,
            final Class<T> messageType) {
        return decodeReader(definition, new ZeroReader(payload), messageType);
    }

    /**
     * 直接解码稳定只读视图；不复制整个 payload，不改变输入游标，线程安全。
     * @param definition 协议定义，不可为空。
     * @param payload 自持有或调用期间保持稳定的视图，不可为空。
     * @param messageType 目标类型，不可为空。
     * @return 自持有 DTO，可变性由类型定义。
     * @throws NullPointerException 必填参数为空。
     * @throws ZeroException 类型不符、输入畸形或存在尾随数据。
     */
    @Override public T decodeView(final ProtocolDefinition definition, final java.nio.ByteBuffer payload,
            final Class<T> messageType) {
        return decodeReader(definition, new ZeroReader(payload), messageType);
    }

    private T decodeReader(final ProtocolDefinition definition, final ZeroReader reader, final Class<T> messageType) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(messageType, "messageType");
        if (!messageType.equals(payloadCodec.messageType())) {
            throw ZeroException.of(
                    ProtocolErrorCode.DECODE_FAILED,
                    "message type does not match payload codec: " + messageType.getName(),
                    null);
        }
        T message = payloadCodec.read(reader);
        if (reader.isReadable()) {
            throw ZeroException.of(
                    ProtocolErrorCode.DECODE_FAILED,
                    "payload has trailing bytes: " + reader.readableBytes(),
                    null);
        }
        return message;
    }
}
