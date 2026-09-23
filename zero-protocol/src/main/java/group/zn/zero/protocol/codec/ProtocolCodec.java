package group.zn.zero.protocol.codec;

import group.zn.zero.core.spi.ZeroProvider;
import group.zn.zero.protocol.ProtocolDefinition;

/**
 * 协议编解码 SPI。
 *
 * @param <T> 协议消息类型。
 * @author zn
 */
public interface ProtocolCodec<T> extends ZeroProvider {

    /**
     * 编码协议消息。
     *
     * @param definition 协议定义；不可为空。
     * @param message 协议消息；不可为空。
     * @return 编码后的字节；不可为空；返回数组可变且无序；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 编码失败时抛出，必须绑定 ErrorCode。
     */
    byte[] encode(ProtocolDefinition definition, T message);

    /**
     * 解码协议消息。
     *
     * @param definition 协议定义；不可为空。
     * @param payload 协议字节；不可为空。
     * @param messageType 目标消息类型；不可为空。
     * @return 解码后的协议消息；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 解码失败时抛出，必须绑定 ErrorCode。
     */
    T decode(ProtocolDefinition definition, byte[] payload, Class<T> messageType);

    /**
     * 从 remaining 区间解码，不变更来源游标；线程安全性与 decode 一致。
     * 默认复制后委托已有数组实现，自定义 codec 无需新增实现；框架 codec 可直接只读解析。
     * @param definition 协议定义；不可为空。
     * @param payload 稳定的输入视图；不可为空，读取期间不得修改内容。
     * @param messageType 目标类型；不可为空。
     * @return 自持有的解码结果；可变性由消息类型决定。
     * @throws NullPointerException 必填参数为空。
     * @throws group.zn.zero.core.error.ZeroException 解码失败。
     */
    default T decodeView(final ProtocolDefinition definition, final java.nio.ByteBuffer payload,
            final Class<T> messageType) {
        byte[] bytes = new byte[payload.remaining()];
        payload.get(payload.position(), bytes);
        return decode(definition, bytes, messageType);
    }
}
