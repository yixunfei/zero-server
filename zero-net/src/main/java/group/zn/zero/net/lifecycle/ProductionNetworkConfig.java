package group.zn.zero.net.lifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 显式启用的生产网络生命周期配置。
 *
 * <p>该配置与 {@code ServerOptions} 独立，只有调用方把它装配进
 * {@link ProductionNetworkLifecycle} 并使用 production TCP 构造时才生效。</p>
 *
 * @param listener 低基数监听入口名；只能包含字母、数字、点、下划线和横线。
 * @param handshakeTimeout 握手超时。
 * @param authenticationTimeout 鉴权与重连协调总超时。
 * @param heartbeatInterval 心跳建议间隔。
 * @param allowedMissedHeartbeats 允许连续丢失心跳次数。
 * @param reconnectWindow 玩家 Actor 重连协调窗口。
 * @param maxInboundFrames 单连接鉴权等待队列与业务 in-flight 总预算上限。
 * @param tlsRequired 是否要求底层连接已完成 TLS 握手。
 * @author zn
 */
public record ProductionNetworkConfig(
        String listener,
        Duration handshakeTimeout,
        Duration authenticationTimeout,
        Duration heartbeatInterval,
        int allowedMissedHeartbeats,
        Duration reconnectWindow,
        int maxInboundFrames,
        boolean tlsRequired) {

    /**
     * 默认握手超时。
     */
    public static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 默认鉴权超时。
     */
    public static final Duration DEFAULT_AUTHENTICATION_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 默认心跳间隔。
     */
    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    /**
     * 默认允许丢失心跳次数。
     */
    public static final int DEFAULT_ALLOWED_MISSED_HEARTBEATS = 2;

    /**
     * 默认重连窗口。
     */
    public static final Duration DEFAULT_RECONNECT_WINDOW = Duration.ofSeconds(30);

    /**
     * 默认单连接入站 frame 预算。
     */
    public static final int DEFAULT_MAX_INBOUND_FRAMES = 1024;

    /**
     * 监听入口名格式。
     */
    private static final Pattern LISTENER_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /**
     * 创建生产网络配置。
     *
     * @throws NullPointerException 当入口名或时间配置为空时抛出。
     * @throws IllegalArgumentException 当入口名、时间、丢失次数或入站预算非法时抛出。
     */
    public ProductionNetworkConfig {
        Objects.requireNonNull(listener, "listener");
        requirePositive(handshakeTimeout, "handshakeTimeout");
        requirePositive(authenticationTimeout, "authenticationTimeout");
        requirePositive(heartbeatInterval, "heartbeatInterval");
        requirePositive(reconnectWindow, "reconnectWindow");
        if (!LISTENER_PATTERN.matcher(listener).matches()) {
            throw new IllegalArgumentException("listener must be a low-cardinality name with 1-64 safe characters");
        }
        if (allowedMissedHeartbeats < 1) {
            throw new IllegalArgumentException("allowedMissedHeartbeats must be positive");
        }
        if (maxInboundFrames < 1) {
            throw new IllegalArgumentException("maxInboundFrames must be positive");
        }
        requireNanos(
                heartbeatInterval.multipliedBy(allowedMissedHeartbeats),
                "heartbeat timeout");
    }

    /**
     * 创建用户已确认的首轮默认配置。
     *
     * @param listener 低基数监听入口名；不可为空。
     * @return 默认配置；不可为空；线程安全。
     */
    public static ProductionNetworkConfig defaults(final String listener) {
        return new ProductionNetworkConfig(
                listener,
                DEFAULT_HANDSHAKE_TIMEOUT,
                DEFAULT_AUTHENTICATION_TIMEOUT,
                DEFAULT_HEARTBEAT_INTERVAL,
                DEFAULT_ALLOWED_MISSED_HEARTBEATS,
                DEFAULT_RECONNECT_WINDOW,
                DEFAULT_MAX_INBOUND_FRAMES,
                false);
    }

    /**
     * 返回心跳关闭阈值。
     *
     * @return 心跳间隔乘以允许丢失次数；不可为空；线程安全。
     */
    public Duration heartbeatTimeout() {
        return heartbeatInterval.multipliedBy(allowedMissedHeartbeats);
    }

    /**
     * 返回替换握手与鉴权超时后的新配置。
     *
     * @param handshake 新握手超时；不可为空且必须为正数。
     * @param authentication 新鉴权超时；不可为空且必须为正数。
     * @return 新配置；不可为空；线程安全。
     */
    public ProductionNetworkConfig withAdmissionTimeouts(
            final Duration handshake,
            final Duration authentication) {
        return new ProductionNetworkConfig(
                listener,
                handshake,
                authentication,
                heartbeatInterval,
                allowedMissedHeartbeats,
                reconnectWindow,
                maxInboundFrames,
                tlsRequired);
    }

    /**
     * 返回替换心跳参数后的新配置。
     *
     * @param interval 新心跳间隔；不可为空且必须为正数。
     * @param missed 新允许丢失次数；必须大于 0。
     * @return 新配置；不可为空；线程安全。
     */
    public ProductionNetworkConfig withHeartbeat(final Duration interval, final int missed) {
        return new ProductionNetworkConfig(
                listener,
                handshakeTimeout,
                authenticationTimeout,
                interval,
                missed,
                reconnectWindow,
                maxInboundFrames,
                tlsRequired);
    }

    /**
     * 返回替换单连接入站预算后的新配置。
     *
     * @param limit 新 frame 预算；必须大于 0。
     * @return 新配置；不可为空；线程安全。
     */
    public ProductionNetworkConfig withMaxInboundFrames(final int limit) {
        return new ProductionNetworkConfig(
                listener,
                handshakeTimeout,
                authenticationTimeout,
                heartbeatInterval,
                allowedMissedHeartbeats,
                reconnectWindow,
                limit,
                tlsRequired);
    }

    /**
     * Returns a copy with the TLS requirement changed.
     *
     * @param required whether the transport must already be TLS protected
     * @return copied configuration
     */
    public ProductionNetworkConfig withTlsRequired(final boolean required) {
        return new ProductionNetworkConfig(
                listener,
                handshakeTimeout,
                authenticationTimeout,
                heartbeatInterval,
                allowedMissedHeartbeats,
                reconnectWindow,
                maxInboundFrames,
                required);
    }
    private static void requirePositive(final Duration value, final String name) {
        Duration current = Objects.requireNonNull(value, name);
        if (current.isZero() || current.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        requireNanos(current, name);
    }

    private static void requireNanos(final Duration value, final String name) {
        try {
            value.toNanos();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(name + " must fit in nanoseconds", ex);
        }
    }
}
