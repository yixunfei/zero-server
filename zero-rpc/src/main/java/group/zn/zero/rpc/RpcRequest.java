package group.zn.zero.rpc;

import java.time.Instant;

/**
 * RPC 请求。
 *
 * @param correlationId 关联 ID。
 * @param replyTopic 响应 topic。
 * @param serviceName 服务名。
 * @param methodName 方法名。
 * @param traceId 链路追踪标识。
 * @param timeoutAt 超时时间。
 * @param mode 调用模式。
 * @param topic 传输 topic；为空时由传输层按服务名生成。
 * @param group 传输消费组；为空时由传输层配置决定。
 * @param partitionKey 分区键；为空时由传输层按 correlationId 处理。
 * @param payload 业务负载。
 * @author zn
 */
public record RpcRequest(
        String correlationId,
        String replyTopic,
        String serviceName,
        String methodName,
        String traceId,
        Instant timeoutAt,
        RpcMode mode,
        String topic,
        String group,
        String partitionKey,
        byte[] payload) {

    /**
     * 创建 RPC 请求。
     *
     * @param correlationId 关联 ID；不可为空。
     * @param replyTopic 响应 topic；不可为空。
     * @param serviceName 服务名；不可为空。
     * @param methodName 方法名；不可为空。
     * @param traceId 链路追踪标识；不可为空。
     * @param timeoutAt 超时时间；不可为空。
     * @param mode 调用模式；不可为空。
     * @param payload 业务负载；可为空。
     * @throws NullPointerException 当标准字段为空时抛出。
     */
    public RpcRequest(
            final String correlationId,
            final String replyTopic,
            final String serviceName,
            final String methodName,
            final String traceId,
            final Instant timeoutAt,
            final RpcMode mode,
            final byte[] payload) {
        this(correlationId, replyTopic, serviceName, methodName, traceId, timeoutAt, mode, "", "", "", payload);
    }

    /**
     * 创建 RPC 请求。
     *
     * @throws NullPointerException 当标准字段为空时抛出。
     */
    public RpcRequest {
        java.util.Objects.requireNonNull(correlationId, "correlationId");
        java.util.Objects.requireNonNull(replyTopic, "replyTopic");
        java.util.Objects.requireNonNull(serviceName, "serviceName");
        java.util.Objects.requireNonNull(methodName, "methodName");
        java.util.Objects.requireNonNull(traceId, "traceId");
        java.util.Objects.requireNonNull(timeoutAt, "timeoutAt");
        java.util.Objects.requireNonNull(mode, "mode");
        topic = topic == null ? "" : topic;
        group = group == null ? "" : group;
        partitionKey = partitionKey == null ? "" : partitionKey;
        payload = payload == null ? new byte[0] : payload.clone();
    }

    /**
     * 返回业务负载副本。
     *
     * @return 可变字节数组副本；可能为空数组；无序；线程安全。
     */
    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
