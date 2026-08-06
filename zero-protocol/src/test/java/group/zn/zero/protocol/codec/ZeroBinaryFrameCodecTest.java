package group.zn.zero.protocol.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.protocol.ProtocolFeature;
import group.zn.zero.protocol.ProtocolFrame;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * zero 二进制协议帧编解码测试。
 *
 * @author zn
 */
class ZeroBinaryFrameCodecTest {

    /**
     * 验证协议帧可以往返编解码。
     */
    @Test
    void frameShouldRoundTrip() {
        ZeroBinaryFrameCodec codec = new ZeroBinaryFrameCodec();
        int flags = ProtocolFeature.toFlags(Set.of(ProtocolFeature.EXTENSION_HEADER));
        ProtocolFrame frame = new ProtocolFrame(
                1001,
                2,
                flags,
                new byte[] {1, 2},
                new byte[] {3, 4, 5});

        ProtocolFrame decoded = codec.decode(codec.encode(frame));

        assertEquals(1001, decoded.protocolId());
        assertEquals(2, decoded.protocolVersion());
        assertEquals(flags, decoded.flags());
        assertArrayEquals(new byte[] {1, 2}, decoded.extension());
        assertArrayEquals(new byte[] {3, 4, 5}, decoded.payload());
    }

    /**
     * 验证非法 magic 会被拒绝。
     */
    @Test
    void invalidMagicShouldFail() {
        ZeroBinaryFrameCodec codec = new ZeroBinaryFrameCodec();
        byte[] bytes = codec.encode(new ProtocolFrame(1, 1, 0, null, new byte[] {1}));
        bytes[0] = 'X';

        assertThrows(ZeroException.class, () -> codec.decode(bytes));
    }

    /**
     * 验证截断帧会被拒绝。
     */
    @Test
    void truncatedFrameShouldFail() {
        ZeroBinaryFrameCodec codec = new ZeroBinaryFrameCodec();

        assertThrows(ZeroException.class, () -> codec.decode(new byte[] {'Z', 'R'}));
    }

    /**
     * 验证 payload 长度限制会被执行。
     */
    @Test
    void payloadLengthLimitShouldFail() {
        ZeroBinaryFrameCodec codec = new ZeroBinaryFrameCodec(16, 2);
        byte[] bytes = codec.encode(new ProtocolFrame(1, 1, 0, null, new byte[] {1, 2}));
        byte[] tooLarge = new ZeroBinaryFrameCodec().encode(
                new ProtocolFrame(1, 1, 0, null, new byte[] {1, 2, 3}));

        assertEquals(2, codec.decode(bytes).payload().length);
        assertThrows(ZeroException.class, () -> codec.decode(tooLarge));
    }

    /**
     * 验证非法协议版本会被转换为协议异常。
     */
    @Test
    void invalidProtocolVersionShouldFail() {
        ZeroBinaryFrameCodec codec = new ZeroBinaryFrameCodec();
        group.zn.zero.protocol.buffer.ZeroWriter writer = new group.zn.zero.protocol.buffer.ZeroWriter();
        writer.writeBytes(ZeroBinaryFrameCodec.MAGIC);
        writer.writeUnsignedInt(ZeroBinaryFrameCodec.FRAME_VERSION);
        writer.writeUnsignedInt(0);
        writer.writeUnsignedInt(0);
        writer.writeUnsignedInt(1);
        writer.writeByteArray(null);
        writer.writeByteArray(new byte[] {1});

        assertThrows(ZeroException.class, () -> codec.decode(writer.toByteArray()));
    }
}
