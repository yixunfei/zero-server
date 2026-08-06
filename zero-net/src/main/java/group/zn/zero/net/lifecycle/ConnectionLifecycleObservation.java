package group.zn.zero.net.lifecycle;

import group.zn.zero.net.error.NetErrorCode;
import java.time.Instant;
import java.util.Objects;

/**
 * 连接生命周期观测事件。
 *
 * <p>{@code listener/protocol/event/result/reason/scope} 是允许进入指标的低基数字段；
 * {@code traceId/connectionId/remoteAddress} 只允许进入受控日志，不得作为 Prometheus 标签。
 * 本记录不承载 token、密码、密钥、完整玩家对象、房间或场景标识。</p>
 *
 * @param time 事件时间。
 * @param listener 低基数监听入口名。
 * @param protocol 固定传输协议名。
 * @param traceId 连接链路标识；仅用于日志。
 * @param connectionId 连接标识；仅用于日志。
 * @param remoteAddress 已脱敏远端地址；仅用于日志。
 * @param state 事件发生后的连接状态。
 * @param event 事件类型。
 * @param result 事件结果。
 * @param reason 有界拒绝原因。
 * @param scope 有界限流范围。
 * @param errorCode 绑定错误码；非错误事件可以为空。
 * @param latencyNanos 当前阶段耗时纳秒；无耗时含义时为 0。
 * @param inboundInFlight 当前入站排队与业务 in-flight 数量。
 * @author zn
 */
public record ConnectionLifecycleObservation(
        Instant time,
        String listener,
        String protocol,
        String traceId,
        String connectionId,
        String remoteAddress,
        ConnectionLifecycleState state,
        ConnectionLifecycleEventType event,
        ConnectionLifecycleResult result,
        ConnectionRejectionReason reason,
        NetworkRateLimitScope scope,
        NetErrorCode errorCode,
        long latencyNanos,
        int inboundInFlight) {

    /**
     * 创建连接生命周期观测事件。
     *
     * @throws NullPointerException 当必需字段为空时抛出。
     * @throws IllegalArgumentException 当耗时或 in-flight 为负数时抛出。
     */
    public ConnectionLifecycleObservation {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(scope, "scope");
        if (latencyNanos < 0L) {
            throw new IllegalArgumentException("latencyNanos must not be negative");
        }
        if (inboundInFlight < 0) {
            throw new IllegalArgumentException("inboundInFlight must not be negative");
        }
    }
}
