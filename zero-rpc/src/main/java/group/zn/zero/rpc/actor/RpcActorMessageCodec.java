package group.zn.zero.rpc.actor;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.error.RpcErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * RPC Actor 消息信封编解码器。
 *
 * <p>该编解码器只负责 Actor 信封字段和业务 payload 字节，不改变 Kafka RPC envelope
 * 线格式。业务 payload 编解码仍复用 `RpcCodecRegistry` 中注册的协议 codec。
 *
 * @author zn
 */
public final class RpcActorMessageCodec {

    /**
     * Actor RPC 信封版本。
     */
    private static final int ENVELOPE_VERSION = 1;

    /**
     * RPC codec 注册表。
     */
    private final RpcCodecRegistry codecRegistry;

    /**
     * payload 类型加载器。
     */
    private final ClassLoader classLoader;

    /**
     * 创建 Actor RPC 消息编解码器。
     *
     * @param codecRegistry RPC codec 注册表；不可为空。
     * @throws NullPointerException 当注册表为空时抛出。
     */
    public RpcActorMessageCodec(final RpcCodecRegistry codecRegistry) {
        this(codecRegistry, Thread.currentThread().getContextClassLoader());
    }

    /**
     * 创建 Actor RPC 消息编解码器。
     *
     * @param codecRegistry RPC codec 注册表；不可为空。
     * @param classLoader payload 类型加载器；为空时使用当前类加载器。
     * @throws NullPointerException 当注册表为空时抛出。
     */
    public RpcActorMessageCodec(final RpcCodecRegistry codecRegistry, final ClassLoader classLoader) {
        this.codecRegistry = Objects.requireNonNull(codecRegistry, "codecRegistry");
        this.classLoader = classLoader == null ? RpcActorMessageCodec.class.getClassLoader() : classLoader;
    }

    /**
     * 编码 Actor 消息。
     *
     * @param message Actor 消息；不可为空。
     * @return 编码后的字节；不可为空；返回数组可变、有序、可能为空、线程安全。
     * @throws ZeroException 当 payload codec 缺失或编码失败时抛出。
     */
    public byte[] encode(final ActorMessage message) {
        ActorMessage current = Objects.requireNonNull(message, "message");
        byte[] payload = encodePayload(current.payload());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 128);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(ENVELOPE_VERSION);
            writeText(output, current.messageId());
            writeText(output, current.laneKey().type());
            writeText(output, current.laneKey().value());
            writeText(output, current.traceId());
            writeText(output, current.payload().getClass().getName());
            output.writeInt(payload.length);
            output.write(payload);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw ZeroException.of(RpcErrorCode.CODEC_FAILED, "encode actor rpc envelope failed", ex);
        }
    }

    /**
     * 解码 Actor 消息。
     *
     * @param bytes 信封字节；不可为空。
     * @return Actor 消息；不可为空；线程安全。
     * @throws ZeroException 当信封版本、payload 类型或 codec 非法时抛出。
     */
    public ActorMessage decode(final byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            int version = input.readInt();
            if (version != ENVELOPE_VERSION) {
                throw ZeroException.of(
                        RpcErrorCode.CODEC_FAILED,
                        "unsupported actor rpc envelope version: " + version,
                        null);
            }
            String messageId = readText(input);
            String laneType = readText(input);
            String laneValue = readText(input);
            String traceId = readText(input);
            Class<?> payloadType = loadPayloadType(readText(input));
            int payloadLength = input.readInt();
            if (payloadLength < 0) {
                throw ZeroException.of(RpcErrorCode.CODEC_FAILED, "negative actor rpc payload length", null);
            }
            byte[] payload = input.readNBytes(payloadLength);
            if (payload.length != payloadLength || input.available() != 0) {
                throw ZeroException.of(RpcErrorCode.CODEC_FAILED, "invalid actor rpc payload length", null);
            }
            return new ActorMessage(messageId, new LaneKey(laneType, laneValue), traceId,
                    decodePayload(payloadType, payload));
        } catch (IOException ex) {
            throw ZeroException.of(RpcErrorCode.CODEC_FAILED, "decode actor rpc envelope failed", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> byte[] encodePayload(final T payload) {
        Objects.requireNonNull(payload, "payload");
        Class<T> payloadType = (Class<T>) payload.getClass();
        return codecRegistry.require(payloadType).encode(payload);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object decodePayload(final Class<?> payloadType, final byte[] payload) {
        return codecRegistry.require((Class) payloadType).decode(payload);
    }

    private Class<?> loadPayloadType(final String className) {
        try {
            return Class.forName(className, true, classLoader);
        } catch (ClassNotFoundException ex) {
            throw ZeroException.of(RpcErrorCode.CODEC_NOT_FOUND,
                    "actor rpc payload class not found: " + className,
                    ex);
        }
    }

    private void writeText(final DataOutputStream output, final String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private String readText(final DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0) {
            throw ZeroException.of(RpcErrorCode.CODEC_FAILED, "negative actor rpc text length", null);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw ZeroException.of(RpcErrorCode.CODEC_FAILED, "invalid actor rpc text length", null);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
