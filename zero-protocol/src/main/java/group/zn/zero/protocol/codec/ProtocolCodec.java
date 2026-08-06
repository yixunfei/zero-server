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
}
