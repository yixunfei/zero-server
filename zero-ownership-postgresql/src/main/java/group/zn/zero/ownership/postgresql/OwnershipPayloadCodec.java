package group.zn.zero.ownership.postgresql;

import java.util.Objects;

/** 将状态值编码为可持久化字节；业务可注入 JSON、协议或自定义格式。 */
public interface OwnershipPayloadCodec<T> {
    /** 编码状态值；返回数组由实现拥有，调用方不会修改。 */
    byte[] encode(T value);

    /** 解码数据库字节；输入可能为空，对应空状态值。 */
    T decode(byte[] payload);

    /** 创建拒绝空字节的严格 codec 包装。 */
    static <T> OwnershipPayloadCodec<T> requireNonNullPayload(final OwnershipPayloadCodec<T> codec) {
        Objects.requireNonNull(codec, "codec");
        return new OwnershipPayloadCodec<>() {
            @Override
            public byte[] encode(final T value) {
                return Objects.requireNonNull(codec.encode(value), "encoded payload");
            }

            @Override
            public T decode(final byte[] payload) {
                return codec.decode(payload);
            }
        };
    }
}
