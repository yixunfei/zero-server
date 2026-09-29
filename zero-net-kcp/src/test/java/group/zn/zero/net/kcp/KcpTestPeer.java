package group.zn.zero.net.kcp;

import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import kcp.Kcp;

/** 手动 pump 的真实 UDP 客户端；可确定性注入丢包/重排/重复，不创建线程。 @author zn */
final class KcpTestPeer implements AutoCloseable {
    /** 客户端 socket。 */
    final DatagramSocket socket;
    /** 私有算法状态。 */
    final Kcp engine;
    /** 包认证状态。 */
    final KcpDatagramCodec wire;
    /** 已收到业务帧，保持顺序。 */
    final List<ProtocolFrame> received = new ArrayList<>();
    /** 故障开关。 */
    boolean lossy;
    /** 已实际丢弃数。 */
    int dropped;
    /** 已交换顺序数。 */
    int reordered;
    /** 服务端至客户端的数据报丢弃数。 */
    int inboundDropped;
    /** 服务端收到的包序号。 */
    private int inboundCount;
    /** 最多喂给算法的数据报数量，用于仅确认一部分分片。 */
    int receiveLimit = Integer.MAX_VALUE;
    /** 已喂给算法的 KCP 数据报数量。 */
    int acceptedInbound;
    /** 超过 receiveLimit 后丢弃的 KCP 数据报数量。 */
    int blockedInbound;
    /** 出站包序号。 */
    private int emitted;
    /** 延后发送用于重排的包。 */
    private byte[] held;
    /** 编解码。 */
    private final ZeroBinaryFrameCodec codec = new ZeroBinaryFrameCodec();

    KcpTestPeer(final KcpTicket ticket, final int port, final KcpOptions options) throws IOException {
        socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        socket.connect(new InetSocketAddress("127.0.0.1", port));
        socket.setSoTimeout(5);
        wire = new KcpDatagramCodec(ticket, false);
        engine = new Kcp(ticket.conv(), (data, ignored) -> output(data));
        options.tuning().apply(engine);
    }
    void send(final ProtocolFrame frame) {
        ByteBuf buffer = Unpooled.wrappedBuffer(codec.encode(frame));
        try { if (engine.send(buffer) != 0) throw new IllegalStateException("test send rejected"); }
        finally { buffer.release(); }
    }
    byte[] heartbeat() {
        ByteBuf packet = wire.encode(UnpooledByteBufAllocator.DEFAULT, Unpooled.EMPTY_BUFFER);
        try { return ByteBufUtil.getBytes(packet); } finally { packet.release(); }
    }
    void raw(final byte[] bytes) throws IOException { socket.send(new DatagramPacket(bytes, bytes.length)); }
    void until(final BooleanSupplier condition) throws IOException {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("KCP condition timed out");
            pump();
        }
    }
    void pump() throws IOException {
        engine.update(System.currentTimeMillis());
        byte[] bytes = new byte[65535];
        DatagramPacket datagram = new DatagramPacket(bytes, bytes.length);
        try { socket.receive(datagram); }
        catch (SocketTimeoutException timeout) { return; /* 有限轮询由总期限控制。 */ }
        if (lossy && ++inboundCount % 11 == 1) { inboundDropped++; return; }
        ByteBuf packet = Unpooled.wrappedBuffer(bytes, 0, datagram.getLength());
        ByteBuf body = wire.decode(packet);
        try {
            if (body != null && body.isReadable()) {
                if (acceptedInbound >= receiveLimit) { blockedInbound++; return; }
                acceptedInbound++;
            }
            if (body != null && body.isReadable() && engine.input(body, true, System.currentTimeMillis()) != 0) {
                throw new AssertionError("server sent invalid KCP");
            }
        } finally { if (body != null) body.release(); packet.release(); }
        while (engine.canRecv()) {
            ByteBuf message = engine.mergeRecv();
            try { received.add(codec.decode(ByteBufUtil.getBytes(message))); } finally { message.release(); }
        }
    }
    private void output(final ByteBuf data) {
        ByteBuf packet = null;
        try {
            packet = wire.encode(UnpooledByteBufAllocator.DEFAULT, data);
            byte[] bytes = ByteBufUtil.getBytes(packet);
            emitted++;
            if (lossy && emitted % 7 == 1) { dropped++; return; }
            if (lossy && emitted % 5 == 0 && held == null) { held = bytes; return; }
            raw(bytes);
            if (lossy && emitted % 3 == 0) raw(bytes);
            if (held != null) { raw(held); held = null; reordered++; }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
        finally { data.release(); if (packet != null) packet.release(); }
    }
    @Override public void close() { socket.close(); engine.release(); wire.close(); }
}
