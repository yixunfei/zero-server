package group.zn.zero.net.kcp;

import io.netty.buffer.ByteBuf;

/** 双端共用的完整 KCP 段边界校验；只读线程安全，调用方保证缓冲生命周期。 @author zn */
final class KcpSegments {
    private KcpSegments() { }
    static boolean valid(final ByteBuf body, final int conv, final KcpOptions options) {
        int offset = body.readerIndex();
        while (offset < body.writerIndex()) {
            if (body.writerIndex() - offset < 24 || body.getIntLE(offset) != conv) return false;
            int command = body.getUnsignedByte(offset + 4);
            int fragment = body.getUnsignedByte(offset + 5);
            int length = body.getIntLE(offset + 20);
            if (length < 0 || length > options.mtu() - 24 || length > body.writerIndex() - offset - 24
                    || command < 81 || command > 84 || fragment >= Math.min(options.window(), 255)
                    || command != 81 && (length != 0 || fragment != 0) || command == 81 && length == 0) return false;
            offset += 24 + length;
        }
        return true;
    }
}
