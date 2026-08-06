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
}
