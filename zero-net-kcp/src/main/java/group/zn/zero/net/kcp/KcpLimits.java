package group.zn.zero.net.kcp;

/**
 * 不可变资源上限；线程安全，服务出站总字节上限另由 NetworkTuning 管理。
 * @param maxSessions 票据和绑定会话总数。
 * @param maxQueuedFrames 单连接业务排队上限，含在途 handler。
 * @param maxPendingSegments 单连接待确认 segment 上限。
 * @param maxFrameBytes 完整 ZeroBinary 帧上限。
 * @param maxInboundBytesTotal 服务业务队列总字节上限。
 * @author zn
 */
public record KcpLimits(int maxSessions, int maxQueuedFrames, int maxPendingSegments,
        int maxFrameBytes, long maxInboundBytesTotal) {
    /** 校验预算；线程安全，无外部副作用，非法范围抛出 IllegalArgumentException。 */
    public KcpLimits {
        if (maxSessions < 1 || maxSessions > 65536 || maxQueuedFrames < 1 || maxQueuedFrames > 1024
                || maxPendingSegments < 32 || maxPendingSegments > 65536 || maxFrameBytes < 1
                || maxInboundBytesTotal < 1024) throw new IllegalArgumentException("invalid KCP limits");
    }
}
