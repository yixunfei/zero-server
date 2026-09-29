package group.zn.zero.net.kcp;

import java.time.Duration;
import java.util.Objects;

/**
 * 独立连接时限；不可变、线程安全。心跳不延长绝对授权或无进展期限。
 * @param bindTimeout 首包/握手超时。
 * @param heartbeatInterval 客户端心跳间隔。
 * @param idleTimeout 无有效数据报超时。
 * @param progressTimeout 无发送确认进展超时。
 * @param ticketLifetime 绝对授权上限。
 * @author zn
 */
public record KcpTimeouts(Duration bindTimeout, Duration heartbeatInterval, Duration idleTimeout,
        Duration progressTimeout, Duration ticketLifetime) {
    /** 校验关系；空值或非法范围抛出标准参数异常；无外部变更。 */
    public KcpTimeouts {
        for (Duration value : new Duration[]{bindTimeout, heartbeatInterval, idleTimeout, progressTimeout, ticketLifetime}) {
            Objects.requireNonNull(value, "timeout");
            if (value.toMillis() < 1 || value.compareTo(Duration.ofHours(24)) > 0) {
                throw new IllegalArgumentException("KCP timeout must be 1ms..24h");
            }
        }
        if (heartbeatInterval.compareTo(idleTimeout) >= 0 || bindTimeout.compareTo(idleTimeout) > 0
                || idleTimeout.compareTo(ticketLifetime) > 0 || progressTimeout.compareTo(ticketLifetime) > 0) {
            throw new IllegalArgumentException("KCP timeout ordering: heartbeat < idle <= ticket; bind <= idle; progress <= ticket");
        }
    }
}
