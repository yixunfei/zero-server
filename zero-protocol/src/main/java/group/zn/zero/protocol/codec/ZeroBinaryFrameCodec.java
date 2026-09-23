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
        ZeroWriter writer = EncodingWriters.acquire(encodedLength(frame));
        try {
            encodeTo(frame, writer);
            return writer.toByteArray();
        } finally {
            EncodingWriters.release(writer);
        }
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
        return decodeFrom(new ZeroReader(bytes));
    }

    /**
     * 直接读取帧边界，数组在长度检查后分配，返回帧自持有副本。
     * @param reader 独占读取器，不可为空；修改其读取位置。
     * @return 不可变帧，线程安全。
     * @throws ZeroException 格式或长度无效。
     */
    @Override public ProtocolFrame decodeFrom(final ZeroReader reader) {
        Objects.requireNonNull(reader, "reader");
        try {
            readHeader(reader);
            int protocolVersion = reader.readUnsignedInt();
            int flags = reader.readUnsignedInt();
            int protocolId = reader.readUnsignedInt();
            byte[] extension = readBounded(reader, maxExtensionLength, "extension");
            byte[] payload = readBounded(reader, maxPayloadLength, "payload");
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

    private void readHeader(final ZeroReader reader) {
        for (byte magicByte : MAGIC) {
            if (reader.readByte() != magicByte) throw invalid("invalid frame magic");
        }
        int version = reader.readUnsignedInt();
        if (version != FRAME_VERSION) throw invalid("unsupported frame version: " + version);
    }

    /**
     * 直接写入目标缓冲，保持线格式；不暴露帧内部数组，线程安全但 writer 须独占。
     * @param frame 不可变帧。
     * @param writer 目标，修改写位置，不持有或关闭。
     * @throws ZeroException 长度无效。
     */
    @Override public void encodeTo(final ProtocolFrame frame, final ZeroWriter writer) {
        encodedLength(frame);
        writer.writeBytes(MAGIC);
        writer.writeUnsignedInt(FRAME_VERSION);
        writer.writeUnsignedInt(frame.protocolVersion());
        writer.writeUnsignedInt(frame.flags());
        writer.writeUnsignedInt(frame.protocolId());
        writer.writeUnsignedInt(frame.extensionLength());
        if (frame.extensionLength() > 0) writer.writeBytes(frame.extensionView());
        writer.writeUnsignedInt(frame.payloadLength());
        if (frame.payloadLength() > 0) writer.writeBytes(frame.payloadView());
    }

    /** @param frame 不可变帧。 @return 精确字节数；只读、线程安全。 @throws ZeroException 超限。 */
    @Override public int encodedLength(final ProtocolFrame frame) {
        validateLength(frame.extensionLength(), maxExtensionLength, "extension");
        validateLength(frame.payloadLength(), maxPayloadLength, "payload");
        long length = 5L + varintLength(frame.protocolVersion()) + varintLength(frame.flags())
                + varintLength(frame.protocolId()) + varintLength(frame.extensionLength()) + frame.extensionLength()
                + varintLength(frame.payloadLength()) + frame.payloadLength();
        if (length > Integer.MAX_VALUE) throw invalid("frame length overflows");
        return (int) length;
    }

    private static int varintLength(final int value) {
        return value == 0 ? 1 : (32 - Integer.numberOfLeadingZeros(value) + 6) / 7;
    }

    private byte[] readBounded(final ZeroReader reader, final int maximum, final String name) {
        int length = reader.readUnsignedInt();
        validateLength(length, maximum, name);
        return reader.readBytes(length);
    }

    private void validateLength(final int length, final int maxLength, final String name) {
        if (length < 0 || length > maxLength) {
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
        return frame.extensionLength() > 0 || ProtocolFeature.EXTENSION_HEADER.enabledIn(frame.flags());
    }
}
