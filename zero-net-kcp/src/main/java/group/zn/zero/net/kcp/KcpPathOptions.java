package group.zn.zero.net.kcp;

import java.time.Duration;

/** 不可变候选路径验证策略；验证前不改变会话地址。 @author zn */
public record KcpPathOptions(boolean enabled, Duration timeout, Duration retryInterval,
        Duration retiredPathLifetime, int maxCandidates) {
    /** 构造校验参数。 */
    public KcpPathOptions {
        if (timeout == null || retryInterval == null || retiredPathLifetime == null || timeout.toMillis() < 100
                || timeout.toSeconds() > 30 || retryInterval.toMillis() < 20 || retryInterval.compareTo(timeout) >= 0
                || retiredPathLifetime.compareTo(timeout) < 0 || retiredPathLifetime.toMinutes() > 5
                || maxCandidates < 1 || maxCandidates > 4) throw new IllegalArgumentException("invalid KCP path options");
    }
    /** @return 严格地址绑定。 */
    public static KcpPathOptions fixed() { return defaults(false); }
    /** @return 开启挑战验证的路径策略。 */
    public static KcpPathOptions validated() { return defaults(true); }
    private static KcpPathOptions defaults(final boolean enabled) { return new KcpPathOptions(enabled, Duration.ofSeconds(3), Duration.ofMillis(200), Duration.ofSeconds(10), 2); }
}
