package group.zn.zero.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import org.junit.jupiter.api.Test;

/** 错误响应经过真实帧编解码仍保持独立标志和请求关联信息。 @author zn */
class ProtocolErrorFramesTest {
    @Test void errorsDoNotPretendToBeCompressedEncryptedOrSigned() {
        var request = new ProtocolFrame(73, 2, 15, new byte[]{9}, new byte[]{1});
        var codec = new ZeroBinaryFrameCodec();
        var response = codec.decode(codec.encode(ProtocolErrorFrames.response(request, "ZERO-NET-REPLAY-DETECTED")));
        assertEquals(73, response.protocolId());
        assertEquals(2, response.protocolVersion());
        assertTrue(ProtocolFeature.ERROR.enabledIn(response.flags()));
        assertFalse(ProtocolFeature.COMPRESSED.enabledIn(response.flags()));
        assertFalse(ProtocolFeature.ENCRYPTED.enabledIn(response.flags()));
        assertFalse(ProtocolFeature.SIGNED.enabledIn(response.flags()));
        assertEquals(0, response.extensionLength());
        assertEquals("error|protocol=73|reason=ZERO-NET-REPLAY-DETECTED",
                new String(response.payload(), StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> ProtocolErrorFrames.response(request, "secret\ninjected"));
    }
}
