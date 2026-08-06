package group.zn.zero.protocol.codec;

import group.zn.zero.core.spi.ZeroProvider;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;

/**
 * 生成代码适配的 payload 编解码 SPI。
 *
 * @param <T> 消息类型。
 * @author zn
 */
public interface ZeroPayloadCodec<T> extends ZeroProvider {

    /**
     * 返回消息类型。
     *
     * @return 消息类型；不可为空；线程安全。
     */
    Class<T> messageType();

    /**
     * 写入 payload。
     *
     * @param writer 写入器；不可为空。
     * @param message 消息；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 编码失败时抛出，必须绑定 ErrorCode。
     */
    void write(ZeroWriter writer, T message);

    /**
     * 读取 payload。
     *
     * @param reader 读取器；不可为空。
     * @return 消息；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 解码失败时抛出，必须绑定 ErrorCode。
     */
    T read(ZeroReader reader);

    /**
     * 估算 payload 大小。
     *
     * @param message 消息；不可为空。
     * @return 估算字节数；线程安全性由实现声明。
     */
    default int estimatedSize(final T message) {
        return 128;
    }
}
