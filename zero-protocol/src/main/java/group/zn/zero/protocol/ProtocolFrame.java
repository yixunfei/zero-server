package group.zn.zero.protocol;

import java.util.Arrays;
import java.util.Objects;

/**
 * 通用协议帧。
 *
 * <p>协议帧用于通信场景封装 payload。纯数据序列化可以直接使用
 * {@code ZeroWriter}/{@code ZeroReader}，无需强制携带协议帧。
 *
 * @param protocolId 协议 ID。
 * @param protocolVersion 协议版本。
 * @param flags 通用特性位。
 * @param extension 扩展头字节。
 * @param payload 负载字节。
 * @author zn
 */
public record ProtocolFrame(
        int protocolId,
        int protocolVersion,
        int flags,
        byte[] extension,
        byte[] payload) {

    /**
     * 空字节数组。
     */
    private static final byte[] EMPTY_BYTES = new byte[0];

    /**
     * 创建通用协议帧。
     *
     * @throws NullPointerException 当 payload 为空时抛出。
     * @throws IllegalArgumentException 当协议 ID、版本或 flags 非法时抛出。
     */
    public ProtocolFrame {
        if (protocolId <= 0) {
            throw new IllegalArgumentException("protocolId must be positive");
        }
        if (protocolVersion <= 0) {
            throw new IllegalArgumentException("protocolVersion must be positive");
        }
        if (flags < 0) {
            throw new IllegalArgumentException("flags must not be negative");
        }
        extension = extension == null ? EMPTY_BYTES : Arrays.copyOf(extension, extension.length);
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
    }

    /**
     * 返回扩展头字节。
     *
     * @return 扩展头副本；不可为空；有序；可能为空；线程安全。
     */
    @Override
    public byte[] extension() {
        return Arrays.copyOf(extension, extension.length);
    }

    /**
     * 返回负载字节。
     *
     * @return 负载副本；不可为空；有序；可能为空；线程安全。
     */
    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
    /** 返回负载字节数；只读、无复制、线程安全。 @return 非负长度。 */
    public int payloadLength() { return payload.length; }
    /** 返回扩展头字节数；只读、无复制、线程安全。 @return 非负长度。 */
    public int extensionLength() { return extension.length; }

    /**
     * 返回不复制的只读负载视图；不能访问底层数组，帧自持有数据，无需释放。
     * @return 新的只读视图；有序、可能为空；内容可跨线程共享，游标需调用方独占。
     */
    public java.nio.ByteBuffer payloadView() { return java.nio.ByteBuffer.wrap(payload).asReadOnlyBuffer(); }

    /**
     * 直接读取自持有 payload，避免 ByteBuffer 适配及字符串字段的中间字节副本。
     * @return 独立读取器；游标线程独占，内容不可变且可安全保留，切片不暴露可写内部数组。
     */
    public group.zn.zero.protocol.buffer.ZeroReader payloadReader() {
        return group.zn.zero.protocol.buffer.ZeroReader.readOnly(payload);
    }

    /**
     * 返回不复制的只读扩展头视图；帧自持有数据，无需释放。
     * @return 新的只读视图；有序、可能为空；内容可跨线程共享，游标需调用方独占。
     */
    public java.nio.ByteBuffer extensionView() { return java.nio.ByteBuffer.wrap(extension).asReadOnlyBuffer(); }
}
