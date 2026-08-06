package group.zn.zero.rpc.observer;

import group.zn.zero.core.error.ErrorCode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * RPC 传输观测事件。
 *
 * <p>该记录用于把 RPC 传输层关键状态交给日志、指标或诊断适配器。事件不得携带 payload 原文、
 * 连接串、密码、token 或 access key 等敏感信息。
 *
 * @param time 事件时间。
 * @param type 事件类型。
 * @param transportName 传输名称。
 * @param correlationId 关联 ID；可为空字符串。
 * @param traceId 链路追踪 ID；可为空字符串。
 * @param serviceName 服务名；可为空字符串。
 * @param methodName 方法名；可为空字符串。
 * @param topic 传输 topic；可为空字符串。
 * @param group 传输 consumer group；可为空字符串。
 * @param errorCode 错误码；正常事件可为空。
 * @param message 事件说明；可为空字符串。
 * @param attributes 扩展属性；不可为空；不可变、无序、可能为空、线程安全。
 * @author zn
 */
public record RpcTransportEvent(
        Instant time,
        RpcTransportEventType type,
        String transportName,
        String correlationId,
        String traceId,
        String serviceName,
        String methodName,
        String topic,
        String group,
        ErrorCode errorCode,
        String message,
        Map<String, String> attributes) {

    /**
     * 创建 RPC 传输观测事件。
     *
     * @throws NullPointerException 当时间、类型、传输名称或属性为空时抛出。
     */
    public RpcTransportEvent {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(type, "type");
        transportName = requireText(transportName, "transportName");
        correlationId = valueOrEmpty(correlationId);
        traceId = valueOrEmpty(traceId);
        serviceName = valueOrEmpty(serviceName);
        methodName = valueOrEmpty(methodName);
        topic = valueOrEmpty(topic);
        group = valueOrEmpty(group);
        message = valueOrEmpty(message);
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    /**
     * 创建当前时间的 RPC 传输事件。
     *
     * @param type 事件类型；不可为空。
     * @param transportName 传输名称；不可为空。
     * @param correlationId 关联 ID；可为空。
     * @param traceId 链路追踪 ID；可为空。
     * @param serviceName 服务名；可为空。
     * @param methodName 方法名；可为空。
     * @param topic 传输 topic；可为空。
     * @param group consumer group；可为空。
     * @param errorCode 错误码；可为空。
     * @param message 事件说明；可为空。
     * @param attributes 扩展属性；不可为空。
     * @return RPC 传输事件；不可为空；线程安全。
     */
    public static RpcTransportEvent now(
            final RpcTransportEventType type,
            final String transportName,
            final String correlationId,
            final String traceId,
            final String serviceName,
            final String methodName,
            final String topic,
            final String group,
            final ErrorCode errorCode,
            final String message,
            final Map<String, String> attributes) {
        return new RpcTransportEvent(
                Instant.now(),
                type,
                transportName,
                correlationId,
                traceId,
                serviceName,
                methodName,
                topic,
                group,
                errorCode,
                message,
                attributes);
    }

    private static String valueOrEmpty(final String value) {
        return value == null ? "" : value;
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
