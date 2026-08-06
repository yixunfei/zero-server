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
}
