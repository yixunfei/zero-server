package group.zn.zero.data.envelope;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import java.util.Arrays;
import java.util.Objects;

/**
 * 数据对象 zcode 信封编解码器。
 *
 * <p>该 codec 只处理信封线格式，业务对象本身由生成式 {@code ZeroPayloadCodec} 处理。
 *
 * @author zn
 */
public final class ZeroDataEnvelopeCodec {

    /**
     * 信封魔数。
     */
    private static final byte[] MAGIC = new byte[] {'Z', 'D', 'O', '1'};

    /**
     * 信封格式版本。
     */
    private static final int ENVELOPE_VERSION = 1;

    /**
     * 创建数据对象 zcode 信封编解码器。
     */
    public ZeroDataEnvelopeCodec() {
    }

    /**
     * 编码数据对象信封。
     *
     * @param envelope 信封；不可为空。
     * @return zcode 信封字节；不可为空；有序；可能为空；线程安全。
     * @throws ZeroException 编码失败时抛出，绑定 `DataErrorCode.WRITE_FAILED`。
     */
    public byte[] encode(final ZeroDataEnvelope envelope) {
        ZeroDataEnvelope current = Objects.requireNonNull(envelope, "envelope");
        try {
            byte[] payload = current.payload();
            ZeroWriter writer = new ZeroWriter(payload.length + 128);
            writer.writeBytes(MAGIC);
            writer.writeUnsignedInt(ENVELOPE_VERSION);
            writer.writeString(current.namespace());
            writer.writeString(current.collection());
            writer.writeString(current.id());
            writer.writeLong(current.version());
            writer.writeUnsignedInt(current.schemaVersion());
            writer.writeUnsignedInt(current.codecVersion());
            writer.writeLong(current.encodedAtEpochMillis());
            writer.writeByteArray(payload);
            return writer.toByteArray();
        } catch (RuntimeException ex) {
            throw dataException(DataErrorCode.WRITE_FAILED, "encode data envelope failed", ex);
        }
    }

    /**
     * 解码数据对象信封。
     *
     * @param bytes zcode 信封字节；不可为空。
     * @return 信封；不可为空；线程安全。
     * @throws ZeroException 解码失败时抛出，绑定 `DataErrorCode.READ_FAILED`。
     */
    public ZeroDataEnvelope decode(final byte[] bytes) {
        byte[] current = Objects.requireNonNull(bytes, "bytes");
        try {
            ZeroReader reader = new ZeroReader(current);
            byte[] magic = reader.readBytes(MAGIC.length);
            if (!Arrays.equals(MAGIC, magic)) {
                throw dataException(DataErrorCode.READ_FAILED, "invalid data envelope magic", null);
            }
            int envelopeVersion = reader.readUnsignedInt();
            if (envelopeVersion != ENVELOPE_VERSION) {
                throw dataException(DataErrorCode.READ_FAILED,
                        "unsupported data envelope version: " + envelopeVersion, null);
            }
            ZeroDataEnvelope envelope = new ZeroDataEnvelope(
                    reader.readString(),
                    reader.readString(),
                    reader.readString(),
                    reader.readLong(),
                    reader.readUnsignedInt(),
                    reader.readUnsignedInt(),
                    reader.readLong(),
                    reader.readByteArray());
            if (reader.isReadable()) {
                throw dataException(DataErrorCode.READ_FAILED, "data envelope has trailing bytes", null);
            }
            return envelope;
        } catch (RuntimeException ex) {
            if (ex instanceof ZeroException zeroException && zeroException.errorCode() == DataErrorCode.READ_FAILED) {
                throw zeroException;
            }
            throw dataException(DataErrorCode.READ_FAILED, "decode data envelope failed", ex);
        }
    }

    private ZeroException dataException(
            final DataErrorCode errorCode,
            final String message,
            final Throwable cause) {
        return ZeroException.of(errorCode, message, cause);
    }
}
