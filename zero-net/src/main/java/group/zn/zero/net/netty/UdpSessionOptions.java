package group.zn.zero.net.netty;

import java.time.Duration;
import java.util.Objects;

/**
 * UDP 远端地址上下文的内存预算；不提供可靠传输或身份认证。
 *
 * @param maxSessions 最多保留的远端地址数量。
 * @param idleTimeout 无数据报时释放地址上下文的时间。
 * @author zn
 */
public record UdpSessionOptions(int maxSessions, Duration idleTimeout) {

    /** 校验会话预算。 */
    public UdpSessionOptions {
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (maxSessions <= 0 || idleTimeout.isZero() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("UDP session capacity and idle timeout must be positive");
        }
    }

    /** @return 默认有界地址上下文配置；不可为空。 */
    public static UdpSessionOptions defaults() {
        return new UdpSessionOptions(4096, Duration.ofMinutes(1));
    }
}
