package group.zn.zero.protocol.codec;

import group.zn.zero.protocol.ProtocolFrame;

/**
 * 协议帧编解码器。
 *
 * @author zn
 */
public interface ProtocolFrameCodec {

    /**
     * 编码协议帧。
     *
     * @param frame 协议帧；不可为空。
     * @return 编码后的字节；不可为空；有序；可能为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 编码失败时抛出，必须绑定 ErrorCode。
     */
    byte[] encode(ProtocolFrame frame);

    /**
     * 解码协议帧。
     *
     * @param bytes 协议帧字节；不可为空。
     * @return 协议帧；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 解码失败时抛出，必须绑定 ErrorCode。
     */
    ProtocolFrame decode(byte[] bytes);

    /**
     * 编码到调用方缓冲；默认支持仅实现数组 API 的自定义 codec，不要求依赖网络库。
     * @param frame 不可变帧，不可为空。
     * @param writer 目标 writer，调用方独占；codec 不持有或关闭它。
     * @throws group.zn.zero.core.error.ZeroException 编码失败。
     */
    default void encodeTo(final ProtocolFrame frame, final group.zn.zero.protocol.buffer.ZeroWriter writer) {
        writer.writeBytes(encode(frame));
    }

    /**
     * 从完整帧边界解码；返回值必须自持有数据，不能借用调用方缓冲。
     * @param reader 调用方独占的完整帧 reader；codec 不持有它。
     * @return 不可变、可跨线程共享的帧，不可为空。
     * @throws group.zn.zero.core.error.ZeroException 解码失败。
     */
    default ProtocolFrame decodeFrom(final group.zn.zero.protocol.buffer.ZeroReader reader) {
        return decode(reader.readBytes(reader.readableBytes()));
    }

    /**
     * 不分配输出地给出编码上界，供网络准入；线程安全性同 codec。
     * @param frame 待编码帧，不可为空。
     * @return 非负上界；-1 表示未知，此时网络按配置最大帧保守预留。
     */
    default int encodedLength(final ProtocolFrame frame) { return -1; }
}
