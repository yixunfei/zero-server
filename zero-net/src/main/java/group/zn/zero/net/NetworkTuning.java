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
 * @author zn
 */
public record NetworkTuning(NetworkTransport transport, int backlog, int writeLowWaterMark,
        int writeHighWaterMark, long maxPendingBytesPerConnection, long maxPendingBytesTotal,
        int flushConsolidationLimit) {
    /** 校验配置；无效抛出 IllegalArgumentException，空 transport 抛出 NullPointerException。 */
    public NetworkTuning {
        java.util.Objects.requireNonNull(transport, "transport");
        if (backlog <= 0 || writeLowWaterMark < 0 || writeHighWaterMark <= writeLowWaterMark
                || maxPendingBytesPerConnection <= 0 || maxPendingBytesTotal <= 0 || flushConsolidationLimit < 0) {
            throw new IllegalArgumentException("invalid network budgets");
        }
    }
    /** @return 默认 NIO、backlog 128、32/64KiB 水位、64/256MiB 出站预算、逐批刷新。 */
    public static NetworkTuning defaults() {
        return new NetworkTuning(NetworkTransport.NIO, 128, 32 * 1024, 64 * 1024,
                64L * 1024 * 1024, 256L * 1024 * 1024, 0);
    }
}
