package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.DatagramPacket;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 真实算法与所属 EventLoop 的接收静默屏障；反射仅获取测试会话，不读取算法私有队列。 @author zn */
class KcpQuiescenceBoundaryTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void delayedFecRecoveryAfterQuietIsAcceptedWithoutClosingSession(final boolean reedSolomon) throws Exception {
        var fec = reedSolomon ? KcpFecOptions.reedSolomon(4, 2) : KcpFecOptions.xor(4);
        var options = KcpServerTest.CONFIG.toBuilder()
                .transport(KcpTransportOptions.of(1, fec, KcpPathOptions.validated())).build();
        try (var fixture = new KcpServerTest.Fixture(options);
                var peer = new KcpTestPeer(fixture.ticket(new KcpTestControl()), fixture.server.boundPort(), options)) {
            peer.raw(peer.heartbeat());
            var connection = fixture.opened.get(2, TimeUnit.SECONDS);
            var session = session(connection);
            fixture.server.channel().eventLoop().submit(() -> {
                var encoded = new java.util.ArrayList<byte[]>();
                try (var encoder = new KcpFecCodec(fec, KcpAlgorithms.defaults().fec(fec), options.mtu(),
                        bytes -> true, bytes -> { }, reason -> { })) {
                    byte[] encodedFrame = new ZeroBinaryFrameCodec().encode(
                            new ProtocolFrame(17, 1, 0, null, new byte[]{1}));
                    var body = KcpReceiveTrackerTest.segment(session.ticket.conv(), 0, 0, encodedFrame);
                    try { encoder.send(io.netty.buffer.ByteBufUtil.getBytes(body), 0, encoded::add); }
                    finally { body.release(); }
                    encoder.tick(fec.flushDelay().toNanos(), encoded::add);
                    input(session, peer, KcpDatagramCodec.QUIESCE, Unpooled.EMPTY_BUFFER);
                    // 丢弃原始数据片，尾组校验片在静默后恢复出新业务 PUSH。
                    input(session, peer, KcpDatagramCodec.FEC, Unpooled.wrappedBuffer(encoded.get(1)));
                    assertFalse(session.closed());
                }
            }).get(2, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (fixture.calls.get() == 0 && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(1, fixture.calls.get());
            assertEquals(0L, fixture.server.snapshot().reasons().getOrDefault("receive.quiescent_push", 0L));
            fixture.server.channel().eventLoop().submit(session::close).get(2, TimeUnit.SECONDS);
            assertEquals(0, fixture.server.pendingFecBytes());
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void partialOrReorderedReceivePreventsDrainAndAckOnlyDoesNotDeliver(final boolean reordered) throws Exception {
        try (var fixture = new KcpServerTest.Fixture(KcpServerTest.CONFIG);
                var peer = new KcpTestPeer(fixture.ticket(new KcpTestControl()), fixture.server.boundPort(), KcpServerTest.CONFIG)) {
            peer.raw(peer.heartbeat());
            var connection = fixture.opened.get(2, TimeUnit.SECONDS);
            var session = session(connection);
            fixture.server.channel().eventLoop().submit(() -> {
                input(session, peer, KcpDatagramCodec.DATA,
                        KcpReceiveTrackerTest.segment(session.ticket.conv(), reordered ? 1 : 0, reordered ? 0 : 1, new byte[]{1}));
                assertFalse(session.drained());
                session.freeze();
                var ack = Unpooled.buffer(24).writeIntLE(session.ticket.conv()).writeByte(82).writeByte(0)
                        .writeShortLE(128).writeIntLE(0).writeIntLE(0).writeIntLE(0).writeIntLE(0);
                input(session, peer, KcpDatagramCodec.DATA, ack);
                assertFalse(session.drained());
                assertFalse(session.closed());
                assertEquals(0, fixture.calls.get());
            }).get(2, TimeUnit.SECONDS);
        }
    }

    @org.junit.jupiter.api.Test
    void frozenSessionIsNotClosedByOrdinarySendProgressDeadline() throws Exception {
        try (var fixture = new KcpServerTest.Fixture(KcpServerTest.CONFIG);
                var peer = new KcpTestPeer(fixture.ticket(new KcpTestControl()), fixture.server.boundPort(), KcpServerTest.CONFIG)) {
            peer.raw(peer.heartbeat());
            var connection = fixture.opened.get(2, TimeUnit.SECONDS);
            var session = session(connection);
            fixture.server.channel().eventLoop().submit(() -> {
                session.freeze();
                setLong(session, "charged", 1L);
                setLong(session, "pendingSince", System.nanoTime()
                        - KcpServerTest.CONFIG.timeouts().progressTimeout().toNanos() - TimeUnit.SECONDS.toNanos(1));
                long now = System.nanoTime();
                session.tick(now);
                assertFalse(session.closed());
                assertTrue(session.nextDeadline(now) > now);
            }).get(2, TimeUnit.SECONDS);
            fixture.server.channel().eventLoop().submit(session::close).get(2, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void frozenOrPeerQuietNewPushClosesWithoutDelivery(final boolean frozen) throws Exception {
        try (var fixture = new KcpServerTest.Fixture(KcpServerTest.CONFIG);
                var peer = new KcpTestPeer(fixture.ticket(new KcpTestControl()), fixture.server.boundPort(), KcpServerTest.CONFIG)) {
            peer.raw(peer.heartbeat());
            var connection = fixture.opened.get(2, TimeUnit.SECONDS);
            var session = session(connection);
            fixture.server.channel().eventLoop().submit(() -> {
                if (frozen) session.freeze();
                else input(session, peer, KcpDatagramCodec.QUIESCE, Unpooled.EMPTY_BUFFER);
                input(session, peer, KcpDatagramCodec.DATA,
                        KcpReceiveTrackerTest.segment(session.ticket.conv(), 0, 0, new byte[]{1}));
                assertTrue(session.closed());
                assertEquals(0, fixture.calls.get());
                assertEquals(1L, fixture.server.snapshot().reasons().get("receive.quiescent_push"));
            }).get(2, TimeUnit.SECONDS);
        }
    }

    private static KcpSession session(final KcpConnection connection) throws Exception {
        var field = KcpConnection.class.getDeclaredField("session");
        field.setAccessible(true);
        return (KcpSession) field.get(connection);
    }

    private static void setLong(final KcpSession session, final String name, final long value) {
        try {
            var field = KcpSession.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setLong(session, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void input(final KcpSession session, final KcpTestPeer peer, final int type, final ByteBuf body) {
        var packet = new DatagramPacket(peer.wire.encode(body.alloc(), type, body),
                (InetSocketAddress) session.owner.channel().localAddress(), (InetSocketAddress) peer.socket.getLocalSocketAddress());
        try { session.input(packet); }
        finally { packet.release(); body.release(); }
    }
}
