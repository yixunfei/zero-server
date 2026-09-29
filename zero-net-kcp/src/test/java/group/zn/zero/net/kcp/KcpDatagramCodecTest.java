package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 独立向量、双向密钥、篡改、重放、代际和 nonce 边界。 @author zn */
class KcpDatagramCodecTest {
    /** Python hashlib/HMAC 独立实现的固定布局。 */
    @Test void matchesIndependentVectorAndRejectsLegacy() throws Exception {
        var ticket = new KcpTicket(123, new byte[32], Instant.EPOCH);
        try (var client = new KcpDatagramCodec(ticket, false); var server = new KcpDatagramCodec(ticket, true)) {
            ByteBuf packet = client.encode(UnpooledByteBufAllocator.DEFAULT, Unpooled.EMPTY_BUFFER);
            try (var source = getClass().getResourceAsStream("/zkcp-v1.hex")) {
                assertArrayEquals(HexFormat.of().parseHex(new String(source.readAllBytes()).trim()), ByteBufUtil.getBytes(packet));
                assertNull(client.decode(packet));
                packet.setByte(packet.writerIndex() - 1, packet.getByte(packet.writerIndex() - 1) ^ 1);
                assertNull(server.decode(packet));
                packet.setByte(packet.writerIndex() - 1, packet.getByte(packet.writerIndex() - 1) ^ 1);
                assertAccepted(server, packet, new byte[0]); assertNull(server.decode(packet));
            } finally { packet.release(); }
            ByteBuf old = Unpooled.buffer(50).writeInt(0x5a4b4350).writeByte(1).writeByte(0).writeInt(123).writeLong(1).writeZero(32);
            try { assertEquals(0, KcpDatagramCodec.conversation(old)); assertNull(server.decode(old)); } finally { old.release(); }
        }
        assertFalse(ticket.toString().contains(HexFormat.of().formatHex(ticket.key())));
    }
    /** 四种保护均可双向收发；认证模式不允许坏包推进窗口或跨代际解密。 */
    @Test void protectsBothDirectionsAndBindsGeneration() {
        for (var protection : List.of(KcpProtectionStrategies.none(), KcpProtectionStrategies.hmac(),
                KcpProtectionStrategies.chacha20(), KcpProtectionStrategies.aesGcm())) {
            var ticket = new KcpTicket(91, new byte[32], Instant.EPOCH, 7);
            try (var client = new KcpDatagramCodec(ticket, false, protection);
                    var server = new KcpDatagramCodec(ticket, true, protection);
                    var next = new KcpDatagramCodec(new KcpTicket(91, new byte[32], Instant.EPOCH, 8), true, protection)) {
                ByteBuf body = Unpooled.wrappedBuffer(new byte[]{1, 9, 27, 81});
                ByteBuf up = client.encode(UnpooledByteBufAllocator.DEFAULT, body);
                ByteBuf down = server.encode(UnpooledByteBufAllocator.DEFAULT, body);
                try {
                    assertNull(next.decode(up)); assertNull(client.decode(up));
                    if (protection.authenticated()) {
                        for (int position : new int[]{6, 12, 16, 24, 32, up.writerIndex() - 1}) {
                            ByteBuf bad = up.copy(); bad.setByte(position, bad.getByte(position) ^ 1);
                            try { assertNull(server.decode(bad)); } finally { bad.release(); }
                        }
                    }
                    assertAccepted(server, up, ByteBufUtil.getBytes(body));
                    assertAccepted(client, down, ByteBufUtil.getBytes(body));
                    assertNull(server.decode(up));
                } finally { body.release(); up.release(); down.release(); }
            }
        }
    }
    /** 乱序只接收一次，窗口之外拒绝；关闭或耗尽序号必须换新票据。 */
    @Test void enforcesReplayWindowSequenceExhaustionAndClose() throws Exception {
        var ticket = new KcpTicket(-1, new byte[32], Instant.EPOCH);
        try (var client = new KcpDatagramCodec(ticket, false); var server = new KcpDatagramCodec(ticket, true)) {
            ByteBuf[] packets = new ByteBuf[70];
            try {
                for (int i = 0; i < packets.length; i++) packets[i] = client.encode(UnpooledByteBufAllocator.DEFAULT, Unpooled.EMPTY_BUFFER);
                for (int i : new int[]{69, 68, 6}) assertAccepted(server, packets[i], new byte[0]);
                assertNull(server.decode(packets[68])); assertNull(server.decode(packets[5]));
                var field = KcpDatagramCodec.class.getDeclaredField("sent"); field.setAccessible(true); field.setLong(client, Long.MAX_VALUE);
                assertThrows(IllegalStateException.class, () -> client.encode(UnpooledByteBufAllocator.DEFAULT, Unpooled.EMPTY_BUFFER));
            } finally { for (ByteBuf packet : packets) if (packet != null) packet.release(); }
            server.close(); assertThrows(IllegalStateException.class, () -> server.encode(UnpooledByteBufAllocator.DEFAULT, Unpooled.EMPTY_BUFFER));
        }
    }
    private static void assertAccepted(final KcpDatagramCodec codec, final ByteBuf packet, final byte[] expected) {
        ByteBuf decoded = codec.decode(packet); assertNotNull(decoded);
        try { assertArrayEquals(expected, ByteBufUtil.getBytes(decoded)); } finally { decoded.release(); }
    }
}
