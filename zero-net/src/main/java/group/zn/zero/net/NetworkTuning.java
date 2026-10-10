package group.zn.zero.net;

/**
 * 网络资源配置；不可变、线程安全，出站预算包含待提交帧及编码上界和固定消息开销。
 * @param transport NIO/AUTO/EPOLL。
 * @param backlog TCP/HTTP accept backlog，实际受 OS 上限约束。
 * @param writeLowWaterMark 可写性低水位字节数。
 * @param writeHighWaterMark 可写性高水位字节数。
 * @param maxPendingBytesPerConnection 单连接待写预算。
 * @param maxPendingBytesTotal 单个服务器所有连接共享预算。
 * @param flushConsolidationLimit 0 表示立即刷新每个响应批次；正数启用 EventLoop 内刷新合并。
 * @param preserveTcpFrameOrder true 时同一 TCP 连接按业务处理及响应写入完成顺序串行；默认 false。
 * @param maxOrderedInboundFrames 顺序模式每连接已准入帧上限，包含活动帧。
 * @param maxOrderedInboundBytes 顺序模式每连接 payload、extension 加每帧 128 字节的预算。
 * @author zn
 */
public record NetworkTuning(NetworkTransport transport, int backlog, int writeLowWaterMark,
        int writeHighWaterMark, long maxPendingBytesPerConnection, long maxPendingBytesTotal,
        int flushConsolidationLimit, boolean preserveTcpFrameOrder,
        int maxOrderedInboundFrames, long maxOrderedInboundBytes) {
    /** 保持既有七参数装配方式，TCP 帧处理默认允许并发。 */
    public NetworkTuning(final NetworkTransport transport, final int backlog, final int writeLowWaterMark,
            final int writeHighWaterMark, final long maxPendingBytesPerConnection,
            final long maxPendingBytesTotal, final int flushConsolidationLimit) {
        this(transport, backlog, writeLowWaterMark, writeHighWaterMark, maxPendingBytesPerConnection,
                maxPendingBytesTotal, flushConsolidationLimit, false, 1024, 16L * 1024 * 1024);
    }
    /** 校验配置；无效抛出 IllegalArgumentException，空 transport 抛出 NullPointerException。 */
    public NetworkTuning {
        java.util.Objects.requireNonNull(transport, "transport");
        if (backlog <= 0 || writeLowWaterMark < 0 || writeHighWaterMark <= writeLowWaterMark
                || maxPendingBytesPerConnection < 128 || maxPendingBytesTotal < 128 || flushConsolidationLimit < 0
                || maxOrderedInboundFrames <= 0 || maxOrderedInboundBytes <= 0) {
            throw new IllegalArgumentException("invalid network budgets");
        }
    }
    /** @return 默认 NIO、backlog 128、32/64KiB 水位、64/256MiB 出站预算、逐批刷新。 */
    public static NetworkTuning defaults() {
        return new NetworkTuning(NetworkTransport.NIO, 128, 32 * 1024, 64 * 1024,
                64L * 1024 * 1024, 256L * 1024 * 1024, 0, false, 1024, 16L * 1024 * 1024);
    }

    /** 返回保留同一 TCP 连接业务与响应顺序的独立配置。 */
    public NetworkTuning withTcpFrameOrderPreserved(final boolean enabled) {
        return new NetworkTuning(transport, backlog, writeLowWaterMark, writeHighWaterMark,
                maxPendingBytesPerConnection, maxPendingBytesTotal, flushConsolidationLimit, enabled,
                maxOrderedInboundFrames, maxOrderedInboundBytes);
    }

    /**
     * 返回独立的顺序入站预算配置；不修改本实例，线程安全。
     * @param frames 包含活动帧的正数上限。
     * @param bytes 包含固定开销的正数字节上限。
     * @return 不可变配置；非空。
     * @throws IllegalArgumentException 上限不是正数。
     */
    public NetworkTuning withOrderedInboundLimits(final int frames, final long bytes) {
        return new NetworkTuning(transport, backlog, writeLowWaterMark, writeHighWaterMark,
                maxPendingBytesPerConnection, maxPendingBytesTotal, flushConsolidationLimit,
                preserveTcpFrameOrder, frames, bytes);
    }
}
