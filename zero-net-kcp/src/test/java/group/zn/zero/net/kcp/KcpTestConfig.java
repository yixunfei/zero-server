package group.zn.zero.net.kcp;

import java.time.Duration;

/** 原安全回归测试的显式预算装配，不属于公开兼容 API。 @author zn */
final class KcpTestConfig {
    private KcpTestConfig() { }
    static KcpOptions create(final int mtu, final int interval, final int window, final int sessions,
            final int queued, final int pending, final Duration ticket, final Duration idle,
            final long inbound, final boolean tls) {
        return KcpOptions.builder().tuning(new KcpTuning(mtu, interval, window, window, true, 2, 100, true, 1))
                .limits(new KcpLimits(sessions, queued, pending, Math.min(window, 255) * (mtu - 24), inbound))
                .timeouts(new KcpTimeouts(idle, idle.dividedBy(3), idle, idle, ticket))
                .requireControlTls(tls).build();
    }
}
