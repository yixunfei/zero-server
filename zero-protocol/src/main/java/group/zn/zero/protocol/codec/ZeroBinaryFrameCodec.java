package group.zn.zero.protocol.codec;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.ProtocolFeature;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.error.ProtocolErrorCode;
import java.util.Objects;

/**
 * zeroServer 默认二进制协议帧编解码器。
 *
 * <pre>
 * frame
 *   -> magic: fixed 4 bytes
 *   -> frameVersion: unsigned varint
 *   -> protocolVersion: unsigned varint
 *   -> flags: unsigned varint
 *   -> protocolId: unsigned varint
 *   -> extensionLength + extension
 *   -> payloadLength + payload
 * </pre>
 *
 * 本编解码器只处理通用 frame，不解释扩展头业务语义。
 *
 * @author zn
 */
public final class ZeroBinaryFrameCodec implements ProtocolFrameCodec {

    /**
     * 当前 frame 版本。
     */
    public static final int FRAME_VERSION = 1;

    /**
     * 默认 magic：ZRO1。
     */
    public static final byte[] MAGIC = new byte[] {'Z', 'R', 'O', '1'};

    /**
     * 最大扩展头字节数。
     */
    private final int maxExtensionLength;

    /**
     * 最大 payload 字节数。
     */
    private final int maxPayloadLength;

    /**
     * 创建默认 frame codec。
     */
    public ZeroBinaryFrameCodec() {
        this(64 * 1024, 16 * 1024 * 1024);
    }

    /**
     * 创建 frame codec。
     *
     * @param maxExtensionLength 最大扩展头长度。
     * @param maxPayloadLength 最大 payload 长度。
     * @throws IllegalArgumentException 当长度限制非法时抛出。
     */
    public ZeroBinaryFrameCodec(final int maxExtensionLength, final int maxPayloadLength) {
        if (maxExtensionLength < 0) {
            throw new IllegalArgumentException("maxExtensionLength must not be negative");
        }
        if (maxPayloadLength < 0) {
            throw new IllegalArgumentException("maxPayloadLength must not be negative");
        }
        this.maxExtensionLength = maxExtensionLength;
        this.maxPayloadLength = maxPayloadLength;
    }

    /**
     * 编码协议帧。
     *
     * @param frame 协议帧；不可为空。
     * @return 编码后的字节；不可为空；有序；可能为空；线程安全。
     * @throws ZeroException 编码失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    public byte[] encode(final ProtocolFrame frame) {
        Objects.requireNonNull(frame, "frame");
        byte[] extension = frame.extension();
        byte[] payload = frame.payload();
        validateLength(extension.length, maxExtensionLength, "extension");
        validateLength(payload.length, maxPayloadLength, "payload");

        ZeroWriter writer = new ZeroWriter(MAGIC.length + extension.length + payload.length + 16);
        writer.writeBytes(MAGIC);
        writer.writeUnsignedInt(FRAME_VERSION);
        writer.writeUnsignedInt(frame.protocolVersion());
        writer.writeUnsignedInt(frame.flags());
        writer.writeUnsignedInt(frame.protocolId());
        writer.writeByteArray(extension);
        writer.writeByteArray(payload);
        return writer.toByteArray();
    }

    /**
     * 解码协议帧。
     *
     * @param bytes 协议帧字节；不可为空。
     * @return 协议帧；不可为空；线程安全。
     * @throws ZeroException 解码失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    public ProtocolFrame decode(final byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            ZeroReader reader = new ZeroReader(bytes);
            for (byte magicByte : MAGIC) {
                if (reader.readByte() != magicByte) {
                    throw invalid("invalid frame magic");
                }
            }
            int frameVersion = reader.readUnsignedInt();
            if (frameVersion != FRAME_VERSION) {
                throw invalid("unsupported frame version: " + frameVersion);
            }
            int protocolVersion = reader.readUnsignedInt();
            int flags = reader.readUnsignedInt();
            int protocolId = reader.readUnsignedInt();
            byte[] extension = reader.readByteArray();
            byte[] payload = reader.readByteArray();
            validateLength(extension.length, maxExtensionLength, "extension");
            validateLength(payload.length, maxPayloadLength, "payload");
            if (!reader.isReadable()) {
                return new ProtocolFrame(protocolId, protocolVersion, flags, extension, payload);
            }
            throw invalid("frame has trailing bytes");
        } catch (ZeroException ex) {
            if (ProtocolErrorCode.INVALID_FRAME.equals(ex.errorCode())) {
                throw ex;
            }
            throw invalid("malformed frame", ex);
        } catch (IllegalArgumentException ex) {
            throw invalid("malformed frame", ex);
        }
    }

    private void validateLength(final int length, final int maxLength, final String name) {
        if (length > maxLength) {
            throw ZeroException.of(
                    ProtocolErrorCode.INVALID_FRAME,
                    name + " length exceeds limit: " + length,
                    null);
        }
    }

    private ZeroException invalid(final String message) {
        return ZeroException.of(ProtocolErrorCode.INVALID_FRAME, message, null);
    }

    private ZeroException invalid(final String message, final Throwable cause) {
        return ZeroException.of(ProtocolErrorCode.INVALID_FRAME, message, cause);
    }

    /**
     * 返回是否携带扩展头。
     *
     * @param frame 协议帧；不可为空。
     * @return true 表示扩展头非空或 flag 声明扩展头；线程安全。
     */
    public boolean hasExtensionHeader(final ProtocolFrame frame) {
        Objects.requireNonNull(frame, "frame");
        return frame.extension().length > 0 || ProtocolFeature.EXTENSION_HEADER.enabledIn(frame.flags());
    }
}
