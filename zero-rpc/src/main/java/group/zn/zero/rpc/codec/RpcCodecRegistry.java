package group.zn.zero.rpc.codec;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.codec.ProtocolCodec;
import group.zn.zero.rpc.error.RpcErrorCode;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * RPC codec 注册表。
 *
 * <p>注册表只保存类型到 `ProtocolCodec` 的绑定，不引入 Kafka 或业务实现。
 *
 * @author zn
 */
public final class RpcCodecRegistry {

    /**
     * 类型到 codec 的绑定表。
     */
    private final ConcurrentMap<Class<?>, RpcCodecBinding<?>> bindings = new ConcurrentHashMap<>();

    /**
     * 注册消息类型 codec。
     *
     * @param messageType 消息类型；不可为空。
     * @param definition 协议定义；不可为空。
     * @param codec 协议 codec；不可为空。
     * @param <T> 消息类型。
     * @throws NullPointerException 当任一参数为空时抛出。
     */
    public <T> void register(
            final Class<T> messageType,
            final ProtocolDefinition definition,
            final ProtocolCodec<T> codec) {
        RpcCodecBinding<T> binding = new RpcCodecBinding<>(messageType, definition, codec);
        bindings.put(messageType, binding);
    }

    /**
     * 判断消息类型是否已有 codec。
     *
     * @param messageType 消息类型；不可为空。
     * @return true 表示已经注册；线程安全。
     */
    public boolean contains(final Class<?> messageType) {
        return bindings.containsKey(Objects.requireNonNull(messageType, "messageType"));
    }

    /**
     * 查找消息类型 codec。
     *
     * @param messageType 消息类型；不可为空。
     * @param <T> 消息类型。
     * @return codec 绑定；不可为空；线程安全。
     * @throws ZeroException 当 codec 缺失时抛出。
     */
    @SuppressWarnings("unchecked")
    public <T> RpcCodecBinding<T> require(final Class<T> messageType) {
        Objects.requireNonNull(messageType, "messageType");
        RpcCodecBinding<?> binding = bindings.get(messageType);
        if (binding == null) {
            throw ZeroException.of(
                    RpcErrorCode.CODEC_NOT_FOUND,
                    "rpc codec not found: " + messageType.getName(),
                    null);
        }
        return (RpcCodecBinding<T>) binding;
    }
}
