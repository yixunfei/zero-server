package group.zn.zero.net.kcp;

/**
 * 算法与数据报参数；不可变、线程安全，拥塞控制默认开启。
 * @param mtu KCP 本体 MTU，认证额外占用 50 字节。
 * @param intervalMillis 算法间隔。
 * @param sendWindow 发送窗口。
 * @param receiveWindow 接收窗口。
 * @param noDelay 是否使用快速模式。
 * @param fastResend 快速重传阈值，0 表示禁用。
 * @param minimumRtoMillis 最小重传等待。
 * @param congestionControl 是否启用拥塞控制。
 * @param flushBatch 每批最多数据报；1 表示立即刷新，其余在当前 EventLoop 批次末刷新。
 * @author zn
 */
public record KcpTuning(int mtu, int intervalMillis, int sendWindow, int receiveWindow,
        boolean noDelay, int fastResend, int minimumRtoMillis, boolean congestionControl, int flushBatch) {
    /** 校验不可变参数；无外部副作用，无效时抛出 IllegalArgumentException。 */
    public KcpTuning {
        if (mtu < 256 || mtu > 1400 || intervalMillis < 10 || intervalMillis > 100
                || sendWindow < 32 || sendWindow > 256 || receiveWindow < 32 || receiveWindow > 256
                || fastResend < 0 || fastResend > 32 || minimumRtoMillis < 10 || minimumRtoMillis > 2000
                || flushBatch < 1 || flushBatch > 256) throw new IllegalArgumentException("invalid KCP tuning");
    }
    /** 将参数应用于独占算法；调用方必须在算法所属执行域，无跨线程共享。 @param engine 算法。 */
    void apply(final kcp.Kcp engine) {
        engine.setMtu(mtu);
        engine.setSndWnd(sendWindow);
        engine.setRcvWnd(receiveWindow);
        engine.nodelay(noDelay, intervalMillis, fastResend, !congestionControl);
        engine.setRxMinrto(minimumRtoMillis);
    }
}
