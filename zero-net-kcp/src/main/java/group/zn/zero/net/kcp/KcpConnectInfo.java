package group.zn.zero.net.kcp;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 可信 TLS 控制面下发的连接描述；不可变、线程安全，编码结果包含密钥，禁止日志记录。
 * ZKCI v1 全部大端，字段布局由本类和固定向量测试共同约束。
 * @param host 客户端可达主机名或 IP，不接受通配地址。
 * @param port 客户端可达 UDP 端口。
 * @param ticket 授权票据。
 * @param options 服务端权威连接配置。
 * @author zn
 */
public record KcpConnectInfo(String host, int port, KcpTicket ticket, KcpOptions options) {
    /** 消息版本魔数。 */
    private static final int MAGIC = 0x5a4b4349;
    /** 校验不可变数据；非法端点抛出参数异常，无网络访问。 */
    public KcpConnectInfo {
        Objects.requireNonNull(host, "host"); Objects.requireNonNull(ticket, "ticket"); Objects.requireNonNull(options, "options");
        if (host.isBlank() || host.length() > 253 || !host.matches("[A-Za-z0-9.:%_-]+")
                || host.equals("0.0.0.0") || host.equals("::") || port < 1 || port > 65535) {
            throw new IllegalArgumentException("advertised KCP endpoint must be concrete and reachable");
        }
    }
    /** @return 解析后的目标端点；可能阻塞 DNS，仅在组合根/连接调用线程使用。 */
    public InetSocketAddress address() {
        InetSocketAddress result = new InetSocketAddress(host, port);
        if (result.isUnresolved()) throw new IllegalArgumentException("unresolved KCP host");
        return result;
    }
    /** @return 独立有序二进制副本，含秘密，仅经安全控制通道下发；线程安全。 */
    public byte[] encode() {
        byte[] name = host.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer data = ByteBuffer.allocate(2048);
        data.putInt(MAGIC).putInt(1).putInt(name.length).put(name).putInt(port);
        data.putInt(ticket.conv()).put(ticket.key()).putLong(ticket.expiresAt().toEpochMilli()).putLong(ticket.generation());
        KcpTuning t = options.tuning();
        data.putInt(options.profile().wireId()).putInt(t.mtu()).putInt(t.intervalMillis());
        data.putInt(t.sendWindow()).putInt(t.receiveWindow()).putInt(t.noDelay() ? 1 : 0);
        data.putInt(t.fastResend()).putInt(t.minimumRtoMillis()).putInt(t.congestionControl() ? 1 : 0).putInt(t.flushBatch());
        KcpLimits l = options.limits();
        data.putInt(l.maxSessions()).putInt(l.maxQueuedFrames()).putInt(l.maxPendingSegments())
                .putInt(l.maxFrameBytes()).putLong(l.maxInboundBytesTotal());
        KcpTimeouts time = options.timeouts();
        data.putLong(time.bindTimeout().toMillis()).putLong(time.heartbeatInterval().toMillis())
                .putLong(time.idleTimeout().toMillis()).putLong(time.progressTimeout().toMillis())
                .putLong(time.ticketLifetime().toMillis()).putInt(options.requireControlTls() ? 1 : 0);
        KcpTransportOptions transport = options.transport();
        data.putInt(transport.protectionId()).putInt(transport.allowUnauthenticated() ? 1 : 0)
                .putInt(transport.maxDatagramBytes()).putLong(transport.maxFecBytesTotal());
        KcpFecOptions fec = transport.fec();
        data.putInt(fec.wireId()).putInt(fec.dataShards()).putInt(fec.parityShards())
                .putLong(fec.flushDelay().toMillis()).putLong(fec.expiry().toMillis())
                .putInt(fec.maxGroups()).putLong(fec.maxBytes());
        KcpPathOptions paths = transport.paths();
        data.putInt(paths.enabled() ? 1 : 0).putLong(paths.timeout().toMillis())
                .putLong(paths.retryInterval().toMillis()).putLong(paths.retiredPathLifetime().toMillis())
                .putInt(paths.maxCandidates());
        return java.util.Arrays.copyOf(data.array(), data.position());
    }
    /**
     * 仅解码已认证控制通道数据，不把此格式当作独立身份认证。
     * @param bytes 大小至多 2048 的完整描述。
     * @return 不可变描述；线程安全，无外部变更。
     * @throws IllegalArgumentException 格式、版本、参数或长度非法。
     */
    public static KcpConnectInfo decode(final byte[] bytes) {
        if (bytes == null || bytes.length > 2048) throw new IllegalArgumentException("invalid KCP description size");
        try {
            ByteBuffer data = ByteBuffer.wrap(bytes);
            if (data.getInt() != MAGIC || data.getInt() != 1) throw new IllegalArgumentException("unsupported KCP description");
            int length = data.getInt();
            if (length < 1 || length > 253) throw new IllegalArgumentException("invalid KCP host length");
            byte[] name = new byte[length]; data.get(name);
            int port = data.getInt(); int conv = data.getInt(); byte[] key = new byte[32]; data.get(key);
            KcpTicket ticket = new KcpTicket(conv, key, Instant.ofEpochMilli(data.getLong()), data.getLong());
            KcpProfile profile = KcpProfile.fromWireId(data.getInt());
            KcpTuning tuning = new KcpTuning(data.getInt(), data.getInt(), data.getInt(), data.getInt(), flag(data),
                    data.getInt(), data.getInt(), flag(data), data.getInt());
            KcpLimits limits = new KcpLimits(data.getInt(), data.getInt(), data.getInt(), data.getInt(), data.getLong());
            KcpTimeouts times = new KcpTimeouts(duration(data), duration(data), duration(data), duration(data), duration(data));
            KcpOptions options = new KcpOptions(profile, tuning, limits, times, flag(data),
                    transport(data));
            if (data.hasRemaining()) throw new IllegalArgumentException("trailing KCP description bytes");
            return new KcpConnectInfo(new String(name, StandardCharsets.US_ASCII), port, ticket, options);
        } catch (java.nio.BufferUnderflowException | java.time.DateTimeException failure) {
            throw new IllegalArgumentException("malformed KCP description", failure);
        }
    }
    private static boolean flag(final ByteBuffer data) {
        int flag = data.getInt();
        if (flag != 0 && flag != 1) throw new IllegalArgumentException("invalid boolean");
        return flag == 1;
    }
    private static Duration duration(final ByteBuffer data) { return Duration.ofMillis(data.getLong()); }
    private static KcpTransportOptions transport(final ByteBuffer data) {
        int protection = data.getInt(); boolean clear = flag(data); int datagram = data.getInt();
        long fecBudget = data.getLong();
        KcpFecOptions fec = new KcpFecOptions(data.getInt(), data.getInt(), data.getInt(),
                duration(data), duration(data), data.getInt(), data.getLong());
        KcpPathOptions paths = new KcpPathOptions(flag(data), duration(data), duration(data), duration(data), data.getInt());
        return new KcpTransportOptions(protection, clear, fec, paths, datagram, fecBudget);
    }
}
