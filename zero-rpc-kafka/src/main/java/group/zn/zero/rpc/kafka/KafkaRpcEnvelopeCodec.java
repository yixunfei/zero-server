package group.zn.zero.rpc.kafka;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.security.SecurityMetadataSnapshot;
import group.zn.zero.rpc.error.RpcErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/**
 * Kafka RPC envelope 编解码器。
 *
 * @author zn
 */
public final class KafkaRpcEnvelopeCodec {

    /**
     * 当前线格式魔数。
     */
    private static final int MAGIC = 0x5A524B31;

    /**
     * 当前线格式版本。
     */
    private static final byte VERSION = 1;

    /**
     * 最大字符串字节数。
     */
    private static final int MAX_STRING_BYTES = 1024 * 1024;

    /**
     * 最大 payload 字节数。
     */
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    /**
     * 编码请求 envelope。
     *
     * @param request RPC 请求；不可为空。
     * @return Kafka value 字节副本；可能为空；无序；线程安全。
     * @throws ZeroException 当编码失败时抛出。
     */
    public byte[] encodeRequest(final RpcRequest request) {
        RpcRequest current = Objects.requireNonNull(request, "request");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeHeader(out, KafkaRpcEnvelopeKind.REQUEST);
            writeString(out, current.correlationId());
            writeString(out, current.replyTopic());
            writeString(out, current.serviceName());
            writeString(out, current.methodName());
            writeString(out, current.traceId());
            out.writeLong(current.timeoutAt().toEpochMilli());
            writeString(out, current.mode().name());
            writeString(out, current.topic());
            writeString(out, current.group());
            writeString(out, current.partitionKey());
            writeMetadata(out, current.securityMetadata());
            writeBytes(out, current.payload());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException | RuntimeException ex) {
            throw ZeroException.of(RpcErrorCode.CODEC_FAILED, "kafka rpc request encode failed", ex);
        }
    }

    /**
     * 编码响应 envelope。
     *
     * @param response RPC 响应；不可为空。
     * @return Kafka value 字节副本；可能为空；无序；线程安全。
     * @throws ZeroException 当编码失败时抛出。
     */
    public byte[] encodeResponse(final RpcResponse response) {
        RpcResponse current = Objects.requireNonNull(response, "response");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeHeader(out, KafkaRpcEnvelopeKind.RESPONSE);
            writeString(out, current.correlationId());
            writeString(out, current.traceId());
            writeString(out, current.errorCode().category().name());
            writeString(out, current.errorCode().code());
            writeString(out, current.errorMessage());
            writeBytes(out, current.payload());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException | RuntimeException ex) {
            throw ZeroException.of(RpcErrorCode.CODEC_FAILED, "kafka rpc response encode failed", ex);
        }
    }

    /**
     * 解码 Kafka value 为 envelope。
     *
     * @param payload Kafka value 字节；不可为空。
     * @return Kafka RPC envelope；不可为空；线程安全。
     * @throws ZeroException 当解码失败时抛出。
     */
    public KafkaRpcEnvelope decode(final byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            KafkaRpcEnvelopeKind kind = readHeader(in);
            if (kind == KafkaRpcEnvelopeKind.REQUEST) {
                return KafkaRpcEnvelope.request(readRequest(in));
            }
            return KafkaRpcEnvelope.response(readResponse(in));
        } catch (IOException | RuntimeException ex) {
            throw ZeroException.of(RpcErrorCode.CODEC_FAILED, "kafka rpc envelope decode failed", ex);
        }
    }

    private void writeHeader(final DataOutputStream out, final KafkaRpcEnvelopeKind kind) throws IOException {
        out.writeInt(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(kind.wireCode());
    }

    private KafkaRpcEnvelopeKind readHeader(final DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("invalid kafka rpc envelope magic");
        }
        byte version = in.readByte();
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported kafka rpc envelope version: " + version);
        }
        return KafkaRpcEnvelopeKind.fromWireCode(in.readByte());
    }

    private RpcRequest readRequest(final DataInputStream in) throws IOException {
        return new RpcRequest(
                readString(in),
                readString(in),
                readString(in),
                readString(in),
                readString(in),
                Instant.ofEpochMilli(in.readLong()),
                RpcMode.valueOf(readString(in)),
                readString(in),
                readString(in),
                readString(in),
                readMetadata(in),
                readBytes(in));
    }

    private RpcResponse readResponse(final DataInputStream in) throws IOException {
        String correlationId = readString(in);
        String traceId = readString(in);
        ErrorCategory category = ErrorCategory.valueOf(readString(in));
        String code = readString(in);
        String message = readString(in);
        return new RpcResponse(correlationId, traceId,
                new KafkaRpcWireErrorCode(category, code, message),
                message,
                readBytes(in));
    }

    private void writeMetadata(final DataOutputStream out, final SecurityMetadataSnapshot metadata) throws IOException {
        out.writeBoolean(metadata != null);
        if (metadata == null) return;
        writeString(out, metadata.subject());
        writeString(out, metadata.transport());
        writeString(out, metadata.peerAddress());
        writeString(out, metadata.trustedSourceAddress());
        writeString(out, metadata.traceId());
        writeString(out, metadata.correlationId());
        writeString(out, String.join("\u001f", metadata.permissions()));
        writeString(out, metadata.assertionReference());
        writeString(out, metadata.signature());
        out.writeLong(metadata.issuedAt().toEpochMilli());
        out.writeLong(metadata.expiresAt().toEpochMilli());
    }

    private SecurityMetadataSnapshot readMetadata(final DataInputStream in) throws IOException {
        if (!in.readBoolean()) return null;
        String subject = readString(in);
        String transport = readString(in);
        String peer = readString(in);
        String trusted = readString(in);
        String trace = readString(in);
        String correlation = readString(in);
        java.util.Set<String> permissions = java.util.Arrays.stream(readString(in).split("\u001f", -1))
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        String assertion = readString(in);
        String signature = readString(in);
        return new SecurityMetadataSnapshot(subject, transport, peer, trusted, trace, correlation,
                permissions, assertion, Instant.ofEpochMilli(in.readLong()), Instant.ofEpochMilli(in.readLong()), signature);
    }
    private void writeString(final DataOutputStream out, final String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("kafka rpc string is too large");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }


    private String readString(final DataInputStream in) throws IOException {
        int length = readLength(in, MAX_STRING_BYTES, "kafka rpc string is too large");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private byte[] readBytes(final DataInputStream in) throws IOException {
        int length = readLength(in, MAX_PAYLOAD_BYTES, "kafka rpc payload is too large");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private int readLength(final DataInputStream in, final int maxLength, final String tooLargeMessage)
            throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IllegalArgumentException("kafka rpc payload length must not be negative");
        }
        if (length > maxLength) {
            throw new IllegalArgumentException(tooLargeMessage);
        }
        return length;
    }

    private void writeBytes(final DataOutputStream out, final byte[] value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value");
        if (bytes.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("kafka rpc payload is too large");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
