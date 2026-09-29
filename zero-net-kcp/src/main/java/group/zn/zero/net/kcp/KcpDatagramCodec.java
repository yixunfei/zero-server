package group.zn.zero.net.kcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * ZKCP v1 数据报保护与 64 包重放窗口；每个端点只由一个 EventLoop 串行调用。
 * 固定头部为 magic/version/direction/type/headerLength/protection/reserved/conv/generation/sequence，
 * 头部作为 AEAD/HMAC AAD，旧字节布局不再接受。
 * @author zn
 */
public final class KcpDatagramCodec implements AutoCloseable {
    /** 固定头部长度。 */
    public static final int HEADER_BYTES = 32;
    /** 默认 HMAC 外层开销。 */
    public static final int OVERHEAD = HEADER_BYTES + 32;
    /** ZKCP 魔数。 */
    private static final int MAGIC = 0x5a4b4350;
    /** 业务数据类型。 */
    static final int DATA = 0;
    /** FEC shard 类型。 */
    static final int FEC = 1;
    /** 路径探测类型。 */
    static final int PROBE = 2;
    /** 路径挑战类型。 */
    static final int CHALLENGE = 3;
    /** 路径响应类型。 */
    static final int RESPONSE = 4;
    /** 客户端已暂停并确认出站 ACK，请求服务端排空。 */
    static final int QUIESCE = 5;
    /** 服务端业务和出站队列已排空。 */
    static final int QUIESCED = 6;
    /** 会话号。 */
    private final int conv;
    /** 出站方向，客户端 0，服务端 1。 */
    private final int direction;
    /** 会话所有权代际。 */
    private final long generation;
    /** 协商保护策略。 */
    private final KcpDatagramProtection protection;
    /** 出站密码上下文。 */
    private final KcpDatagramProtection.Context sender;
    /** 入站密码上下文。 */
    private final KcpDatagramProtection.Context receiver;
    /** 发送序号。 */
    private long sent;
    /** 已接收最大序号。 */
    private long received;
    /** 以 received 为基准的重放位图。 */
    private long bitmap;
    /** 最近一次认证帧类型；仅所属 EventLoop 读取。 */
    private int lastType;
    /** 是否已清除密钥。 */
    private boolean closed;

    /** 创建默认 HMAC-SHA256 的 ZKCP v1 端点。 */
    public KcpDatagramCodec(final KcpTicket ticket, final boolean server) {
        this(ticket, server, KcpTransportOptions.defaults());
    }
    /** 创建带可插拔保护策略的端点。 */
    public KcpDatagramCodec(final KcpTicket ticket, final boolean server,
            final KcpTransportOptions options) {
        this(ticket, server, KcpAlgorithms.defaults().protection(options.protectionId()));
    }
    /** 自定义保护端点；策略在启动前校验，单执行域，不创建线程。 */
    public KcpDatagramCodec(final KcpTicket ticket, final boolean server, final KcpDatagramProtection strategy) {
        Objects.requireNonNull(ticket, "ticket");
        conv = ticket.conv(); direction = server ? 1 : 0; generation = ticket.generation();
        protection = Objects.requireNonNull(strategy);
        sender = context(ticket, direction);
        try { receiver = context(ticket, 1 - direction); }
        catch (RuntimeException failure) { sender.close(); throw failure; }
    }

    /** @return 头部加标签的固定开销；线程安全。 */
    public int overhead() { return HEADER_BYTES + protection.tagBytes(); }
    /** @return 当前保护 wire ID。 */
    public int protectionId() { return protection.wireId(); }
    /** @return 当前代际。 */
    public long generation() { return generation; }

    /** 签名或加密新数据报；body 游标和引用计数不变，返回缓冲所有权转移给调用者。 */
    public ByteBuf encode(final ByteBufAllocator allocator, final ByteBuf body) {
        return encode(allocator, DATA, body);
    }
    /** 编码带控制类型的数据报；仅传输管线调用。 */
    ByteBuf encode(final ByteBufAllocator allocator, final int type, final ByteBuf body) {
        if (closed || sent == Long.MAX_VALUE) throw new IllegalStateException("KCP closed or nonce exhausted");
        if (type < DATA || type > QUIESCED || body.readableBytes() > 8192) throw new IllegalArgumentException("invalid UDP body");
        long sequence = ++sent;
        byte[] plain = new byte[body.readableBytes()]; body.getBytes(body.readerIndex(), plain);
        byte[] header = header(sequence, type);
        byte[] secured = sender.seal(nonce(sequence), header, plain);
        ByteBuf result = allocator.buffer(header.length + secured.length);
        try { result.writeBytes(header).writeBytes(secured); return result; }
        catch (RuntimeException failure) { result.release(); throw failure; }
        finally { Arrays.fill(plain, (byte) 0); Arrays.fill(header, (byte) 0); Arrays.fill(secured, (byte) 0); }
    }

    /** 验证方向、代际、标签和重放窗口；成功返回独立 body，调用者必须释放。 */
    public ByteBuf decode(final ByteBuf packet) {
        int start = packet.readerIndex();
        if (closed || packet.readableBytes() > 8192 || packet.readableBytes() < overhead() || conversation(packet) != conv
                || packet.getUnsignedByte(start + 5) != 1 - direction
                || packet.getUnsignedShort(start + 8) != protection.wireId()
                || packet.getLong(start + 16) != generation) return null;
        long sequence = packet.getLong(start + 24);
        if (sequence <= 0 || duplicate(sequence)) return null;
        byte[] header = new byte[HEADER_BYTES]; packet.getBytes(start, header);
        byte[] secured = new byte[packet.readableBytes() - HEADER_BYTES]; packet.getBytes(start + HEADER_BYTES, secured);
        byte[] plain = receiver.open(nonce(sequence), header, secured);
        Arrays.fill(header, (byte) 0); Arrays.fill(secured, (byte) 0);
        if (plain == null || plain.length != packet.readableBytes() - overhead()) return null;
        accept(sequence);
        lastType = packet.getUnsignedByte(start + 6);
        try { return packet.alloc().buffer(plain.length).writeBytes(plain); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    /** 只解析未经认证的会话号；非法或旧布局返回 0。 */
    public static int conversation(final ByteBuf packet) {
        int start = packet.readerIndex();
        if (packet.readableBytes() < HEADER_BYTES || packet.getInt(start) != MAGIC
                || packet.getUnsignedByte(start + 4) != 1 || packet.getUnsignedByte(start + 7) != HEADER_BYTES
                || packet.getUnsignedShort(start + 10) != 0 || packet.getUnsignedByte(start + 5) > 1
                || packet.getUnsignedByte(start + 6) > QUIESCED) return 0;
        return packet.getInt(start + 12);
    }

    /** @return 已发序号；单 EventLoop 读取。 */
    long sentSequence() { return sent; }
    /** @return 已收最高序号；单 EventLoop 读取。 */
    long receivedSequence() { return received; }
    /** @return 最近一次认证的帧类型。 */
    int lastType() { return lastType; }
    /** @return 独立重放窗口摘要。 */
    byte[] receiveWindow() { return ByteBuffer.allocate(8).putLong(bitmap).array(); }

    private boolean duplicate(final long sequence) {
        if (sequence > received) return false;
        long distance = received - sequence;
        return distance >= 64 || (bitmap & (1L << (int) distance)) != 0;
    }
    private void accept(final long sequence) {
        if (sequence > received) {
            long shift = sequence - received; bitmap = shift >= 64 ? 1 : (bitmap << (int) shift) | 1; received = sequence;
        } else bitmap |= 1L << (int) (received - sequence);
    }
    private byte[] header(final long sequence, final int type) {
        return ByteBuffer.allocate(HEADER_BYTES).putInt(MAGIC).put((byte) 1).put((byte) direction)
                .put((byte) type).put((byte) HEADER_BYTES).putShort((short) protection.wireId()).putShort((short) 0)
                .putInt(conv).putLong(generation).putLong(sequence).array();
    }
    private byte[] nonce(final long sequence) { return ByteBuffer.allocate(12).putInt(conv).putLong(sequence).array(); }
    private KcpDatagramProtection.Context context(final KcpTicket ticket, final int trafficDirection) {
        byte[] salt = ByteBuffer.allocate(14).putInt(conv).putLong(generation).putShort((short) protection.wireId()).array();
        byte[] info = ("ZKCP/v1/traffic/" + trafficDirection).getBytes(StandardCharsets.US_ASCII);
        byte[] master = ticket.key();
        byte[] key = KcpKeyDerivation.derive(master, salt, info, 32);
        try { return protection.create(key); } finally { Arrays.fill(key, (byte) 0); Arrays.fill(master, (byte) 0); }
    }
    private static KcpDatagramProtection strategy(final int id) {
        return switch (id) { case 0 -> KcpProtectionStrategies.none(); case 1 -> KcpProtectionStrategies.hmac();
            case 2 -> KcpProtectionStrategies.chacha20(); case 3 -> KcpProtectionStrategies.aesGcm();
            default -> throw new IllegalArgumentException("unsupported UDP protection " + id); };
    }
    /** 清除方向密钥；幂等。 */
    @Override public void close() { if (!closed) { closed = true; sender.close(); receiver.close(); } }
}
