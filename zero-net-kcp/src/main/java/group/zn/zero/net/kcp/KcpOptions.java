package group.zn.zero.net.kcp;

import java.time.Duration;
import java.util.Objects;

/**
 * 场景化不可变配置；线程安全，构造即验证完整预算。
 * @param profile 预设来源；覆盖后以实际分组值为准。
 * @param tuning 算法参数。
 * @param limits 资源预算。
 * @param timeouts 连接时限。
 * @param requireControlTls 是否要求 TLS 控制通道。
 * @author zn
 */
public record KcpOptions(KcpProfile profile, KcpTuning tuning, KcpLimits limits,
        KcpTimeouts timeouts, boolean requireControlTls, KcpTransportOptions transport) {
    /** 保留五参数装配入口；新代码应显式选择传输策略。 */
    public KcpOptions(final KcpProfile profile, final KcpTuning tuning, final KcpLimits limits,
            final KcpTimeouts timeouts, final boolean requireControlTls) {
        this(profile, tuning, limits, timeouts, requireControlTls, KcpTransportOptions.defaults());
    }
    /** 校验组合；无外部变更，空值/非法组合抛出标准参数异常。 */
    public KcpOptions {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(tuning, "tuning");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(timeouts, "timeouts");
        Objects.requireNonNull(transport, "transport");
        if (limits.maxPendingSegments() < tuning.sendWindow()
                || limits.maxFrameBytes() > Math.min(tuning.receiveWindow(), 255) * (tuning.mtu() - 24)
                || timeouts.idleTimeout().toMillis() < tuning.intervalMillis() * 2L
                || timeouts.progressTimeout().toMillis() < tuning.intervalMillis() * 2L) {
            throw new IllegalArgumentException("inconsistent KCP window/frame/timeout budgets");
        }
    }
    /** @return 均衡默认配置；只读线程安全。 */
    public static KcpOptions defaults() { return builder().build(); }
    /** @return 独立可变 builder，不可跨线程共享。 */
    public static Builder builder() { return builder(KcpProfile.BALANCED); }
    /** @param profile 场景。 @return 独立可变 builder，参数不可为空。 */
    public static Builder builder(final KcpProfile profile) { return new Builder(profile); }
    /** @return 当前配置的独立 builder，不可跨线程共享。 */
    public Builder toBuilder() { return new Builder(this); }
    /** @return 本体 MTU；只读线程安全。 */
    public int mtu() { return tuning.mtu(); }
    /** @return 算法间隔；只读线程安全。 */
    public int intervalMillis() { return tuning.intervalMillis(); }
    /** @return 接收窗口；只读线程安全。 */
    public int window() { return tuning.receiveWindow(); }
    /** @return 会话上限；只读线程安全。 */
    public int maxSessions() { return limits.maxSessions(); }
    /** @return 业务帧上限；只读线程安全。 */
    public int maxQueuedFrames() { return limits.maxQueuedFrames(); }
    /** @return 待确认 segment 上限；只读线程安全。 */
    public int maxPendingSegments() { return limits.maxPendingSegments(); }
    /** @return 票据时限；只读线程安全。 */
    public Duration ticketLifetime() { return timeouts.ticketLifetime(); }
    /** @return 空闲时限；只读线程安全。 */
    public Duration idleTimeout() { return timeouts.idleTimeout(); }
    /** @return 业务总预算；只读线程安全。 */
    public long maxInboundBytesTotal() { return limits.maxInboundBytesTotal(); }
    /** @return 完整消息上限；只读线程安全。 */
    public int maxMessageBytes() { return limits.maxFrameBytes(); }

    /** 配置构建器，只修改自身，不创建资源，不可跨线程共享。 @author zn */
    public static final class Builder {
        /** 预设来源。 */
        private final KcpProfile profile;
        /** 算法设置。 */
        private KcpTuning tuning;
        /** 资源设置。 */
        private KcpLimits limits;
        /** 时限设置。 */
        private KcpTimeouts timeouts;
        /** TLS 要求。 */
        private boolean tls = true;
        private Builder(final KcpProfile source) {
            profile = Objects.requireNonNull(source, "profile");
            int interval = 20, mtu = 1200, window = 128, flush = 16, rto = 100;
            int idle = 30, heartbeat = 10, progress = 30, frame = 32768, queued = 32;
            boolean fast = true;
            switch (source) {
                case LOW_LATENCY -> { interval = 10; window = 64; flush = 1; rto = 30; frame = 8192; queued = 8; }
                case MOBILE -> { mtu = 1100; flush = 8; idle = 60; heartbeat = 15; progress = 45; }
                case LOW_FREQUENCY -> { interval = 50; window = 64; fast = false; idle = 120;
                    heartbeat = 30; progress = 60; frame = 16384; }
                case BULK -> { interval = 40; window = 256; fast = false; flush = 32; frame = 131072; }
                case BALANCED -> { /* 通用均衡设置。 */ }
            }
            tuning = new KcpTuning(mtu, interval, window, window, fast, 2, rto, true, flush);
            limits = new KcpLimits(1024, queued, 512, frame, 64L * 1024 * 1024);
            timeouts = new KcpTimeouts(Duration.ofSeconds(10), Duration.ofSeconds(heartbeat),
                    Duration.ofSeconds(idle), Duration.ofSeconds(progress), Duration.ofMinutes(30));
            transport = KcpTransportOptions.defaults();
        }
        private Builder(final KcpOptions source) {
            profile = source.profile; tuning = source.tuning; limits = source.limits;
            timeouts = source.timeouts; tls = source.requireControlTls; transport = source.transport;
        }
        /** 传输保护、FEC 与路径迁移策略。 */
        private KcpTransportOptions transport;
        /** @param value 算法设置。 @return 自身，修改构建状态；不可为空。 */
        public Builder tuning(final KcpTuning value) { tuning = Objects.requireNonNull(value); return this; }
        /** @param value 资源设置。 @return 自身，修改构建状态；不可为空。 */
        public Builder limits(final KcpLimits value) { limits = Objects.requireNonNull(value); return this; }
        /** @param value 时限设置。 @return 自身，修改构建状态；不可为空。 */
        public Builder timeouts(final KcpTimeouts value) { timeouts = Objects.requireNonNull(value); return this; }
        /** @param value 是否强制 TLS，关闭只适用于可信测试环境。 @return 自身。 */
        public Builder requireControlTls(final boolean value) { tls = value; return this; }
        /** @param value 传输策略。 @return 自身。 */
        public Builder transport(final KcpTransportOptions value) { transport = Objects.requireNonNull(value); return this; }
        /** @return 校验后的不可变配置；无外部修改，非法组合抛出参数异常。 */
        public KcpOptions build() { return new KcpOptions(profile, tuning, limits, timeouts, tls, transport); }
    }
}
