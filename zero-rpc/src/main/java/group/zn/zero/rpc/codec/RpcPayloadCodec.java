package group.zn.zero.rpc.codec;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.rpc.descriptor.RpcMethodDescriptor;
import group.zn.zero.rpc.error.RpcErrorCode;
import java.util.List;
import java.util.Objects;

/**
 * RPC 参数与结果 payload 编解码工具。
 *
 * <p>该工具只做参数 envelope，单个参数和结果仍委托 `ProtocolCodec` 完成。
 *
 * @author zn
 */
public final class RpcPayloadCodec {

    private RpcPayloadCodec() {
    }

    /**
     * 编码 RPC 参数列表。
     *
     * @param descriptor RPC 方法描述符；不可为空。
     * @param arguments 参数数组；可为空，按空参数处理。
     * @return 参数 payload；有序、可能为空、可变、线程安全。
     * @throws ZeroException 当参数数量不匹配或 codec 失败时抛出。
     */
    public static byte[] encodeArguments(final RpcMethodDescriptor descriptor, final Object[] arguments) {
        RpcMethodDescriptor current = Objects.requireNonNull(descriptor, "descriptor");
        Object[] values = arguments == null ? new Object[0] : arguments;
        List<RpcCodecBinding<?>> codecs = current.parameterCodecs();
        if (values.length != codecs.size()) {
            throw ZeroException.of(
                    RpcErrorCode.INVALID_REQUEST,
                    "rpc argument count mismatch: " + current.methodName(),
                    null);
        }
        try {
            ZeroWriter writer = new ZeroWriter();
            writer.writeUnsignedInt(values.length);
            for (int index = 0; index < values.length; index++) {
                writer.writeByteArray(encodeValue(codecs.get(index), values[index]));
            }
            return writer.toByteArray();
        } catch (ZeroException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw ZeroException.of(RpcErrorCode.CODEC_FAILED, "rpc arguments encode failed", ex);
        }
    }

    /**
     * 解码 RPC 参数列表。
     *
     * @param descriptor RPC 方法描述符；不可为空。
     * @param payload 参数 payload；不可为空。
     * @return 参数数组；有序、可能为空数组、可变、线程安全。
     * @throws ZeroException 当参数数量不匹配或 codec 失败时抛出。
     */
    public static Object[] decodeArguments(final RpcMethodDescriptor descriptor, final byte[] payload) {
        RpcMethodDescriptor current = Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(payload, "payload");
        List<RpcCodecBinding<?>> codecs = current.parameterCodecs();
        try {
            ZeroReader reader = new ZeroReader(payload);
            int count = reader.readUnsignedInt();
            if (count != codecs.size()) {
                throw ZeroException.of(
                        RpcErrorCode.INVALID_REQUEST,
                        "rpc argument count mismatch: " + current.methodName(),
                        null);
            }
            Object[] values = new Object[count];
            for (int index = 0; index < count; index++) {
                values[index] = decodeValue(codecs.get(index), reader.readByteArray());
            }
            if (reader.isReadable()) {
                throw ZeroException.of(RpcErrorCode.CODEC_FAILED, "rpc arguments payload has trailing bytes", null);
            }
            return values;
        } catch (ZeroException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw ZeroException.of(RpcErrorCode.CODEC_FAILED, "rpc arguments decode failed", ex);
        }
    }

    /**
     * 编码 RPC 成功结果。
     *
     * @param descriptor RPC 方法描述符；不可为空。
     * @param result 业务结果；Void 结果可为空，其他结果不可为空。
     * @return 结果 payload；有序、可能为空、可变、线程安全。
     */
    public static byte[] encodeResult(final RpcMethodDescriptor descriptor, final Object result) {
        RpcMethodDescriptor current = Objects.requireNonNull(descriptor, "descriptor");
        if (current.returnsVoid()) {
            return new byte[0];
        }
        RpcCodecBinding<?> codec = current.resultCodec();
        if (codec == null) {
            throw ZeroException.of(RpcErrorCode.CODEC_NOT_FOUND, "rpc result codec not found", null);
        }
        try {
            return encodeValue(codec, result);
        } catch (ZeroException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw ZeroException.of(RpcErrorCode.CODEC_FAILED, "rpc result encode failed", ex);
        }
    }

    /**
     * 解码 RPC 成功结果。
     *
     * @param descriptor RPC 方法描述符；不可为空。
     * @param payload 结果 payload；不可为空。
     * @return 业务结果；Void 结果为 null。
     */
    public static Object decodeResult(final RpcMethodDescriptor descriptor, final byte[] payload) {
        RpcMethodDescriptor current = Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(payload, "payload");
        if (current.returnsVoid()) {
            return null;
        }
        RpcCodecBinding<?> codec = current.resultCodec();
        if (codec == null) {
            throw ZeroException.of(RpcErrorCode.CODEC_NOT_FOUND, "rpc result codec not found", null);
        }
        try {
            return decodeValue(codec, payload);
        } catch (ZeroException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw ZeroException.of(RpcErrorCode.CODEC_FAILED, "rpc result decode failed", ex);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static byte[] encodeValue(final RpcCodecBinding binding, final Object value) {
        if (value == null) {
            throw ZeroException.of(RpcErrorCode.CODEC_FAILED, "rpc value must not be null", null);
        }
        return binding.encode(value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object decodeValue(final RpcCodecBinding binding, final byte[] payload) {
        return binding.decode(payload);
    }
}
