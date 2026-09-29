package group.zn.zero.net.kcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

/** 每端点传输管线：KCP -> FEC -> 保护；入站顺序相反，单 EventLoop 独占。 @author zn */
final class KcpTransport implements AutoCloseable {
    /** 方向保护与重放状态。 */
    private final KcpDatagramCodec wire;
    /** 可选 FEC 状态。 */
    private final KcpFecCodec fec;
    /** 原始数据报上限。 */
    private final int maximum;
    /** 固定原因指标。 */
    private final Consumer<String> metric;
    KcpTransport(final KcpTicket ticket, final boolean server, final KcpOptions options,
            final KcpAlgorithms algorithms, final LongPredicate reserve, final LongConsumer release,
            final Consumer<String> metric) {
        algorithms.validate(options); maximum = options.transport().maxDatagramBytes(); this.metric = metric;
        wire = new KcpDatagramCodec(ticket, server, algorithms.protection(options.transport().protectionId()));
        var coding = algorithms.fec(options.transport().fec());
        fec = coding == null ? null : new KcpFecCodec(options.transport().fec(), coding, options.mtu(), reserve, release, metric);
    }
    ByteBuf decode(final ByteBuf packet) {
        if (packet.readableBytes() > maximum) { metric.accept("reject.oversize"); return null; }
        ByteBuf body = wire.decode(packet);
        if (body == null) metric.accept("reject.authentication_or_replay");
        return body;
    }
    /** @return 最近认证的数据类型。 */
    int type() { return wire.lastType(); }
    void input(final int type, final ByteBuf body, final Consumer<ByteBuf> deliver) {
        if (type == KcpDatagramCodec.DATA && (!body.isReadable() || fec == null)) deliver.accept(body);
        else if (type == KcpDatagramCodec.FEC && fec != null) {
            fec.input(ByteBufUtil.getBytes(body), System.nanoTime(), bytes -> {
                ByteBuf recovered = Unpooled.wrappedBuffer(bytes);
                try { deliver.accept(recovered); } finally { recovered.release(); }
            });
        } else metric.accept("reject.packet_type");
    }
    void send(final ByteBufAllocator allocator, final ByteBuf body, final Consumer<ByteBuf> emit) {
        if (fec == null || !body.isReadable()) emit.accept(wire.encode(allocator, body));
        else fec.send(ByteBufUtil.getBytes(body), System.nanoTime(), bytes -> emitFec(allocator, bytes, emit));
    }
    ByteBuf control(final ByteBufAllocator allocator, final int type, final byte[] body) {
        ByteBuf buffer = Unpooled.wrappedBuffer(body);
        try { return wire.encode(allocator, type, buffer); }
        finally { buffer.release(); }
    }
    void tick(final ByteBufAllocator allocator, final long now, final Consumer<ByteBuf> emit) {
        if (fec != null) fec.tick(now, bytes -> emitFec(allocator, bytes, emit));
    }
    private void emitFec(final ByteBufAllocator allocator, final byte[] body, final Consumer<ByteBuf> emit) {
        emit.accept(control(allocator, KcpDatagramCodec.FEC, body));
    }
    long deadline() { return fec == null ? Long.MAX_VALUE : fec.deadline(); }
    boolean sending() { return fec != null && fec.sending(); }
    long sentSequence() { return wire.sentSequence(); }
    long receivedSequence() { return wire.receivedSequence(); }
    byte[] receiveWindow() { return wire.receiveWindow(); }
    /** 幂等释放所有端点状态；单执行域。 */
    @Override public void close() { try { wire.close(); } finally { if (fec != null) fec.close(); } }
}
