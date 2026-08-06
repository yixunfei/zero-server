package group.zn.zero.rpc;

import group.zn.zero.core.error.ErrorCode;

/**
 * RPC 响应。
 *
 * @param correlationId 关联 ID。
 * @param traceId 链路追踪标识。
 * @param errorCode 错误码。
 * @param errorMessage 错误说明。
 * @param payload 响应负载。
 * @author zn
 */
public record RpcResponse(String correlationId, String traceId, ErrorCode errorCode, String errorMessage, byte[] payload) {

    /**
     * 创建 RPC 响应。
     *
     * @param correlationId 关联 ID；不可为空。
     * @param traceId 链路追踪标识；不可为空。
     * @param errorCode 错误码；不可为空。
     * @param payload 响应负载；可为空。
     * @throws NullPointerException 当标准字段为空时抛出。
     */
    public RpcResponse(
            final String correlationId,
            final String traceId,
            final ErrorCode errorCode,
            final byte[] payload) {
        this(correlationId, traceId, errorCode,
                java.util.Objects.requireNonNull(errorCode, "errorCode").message(),
                payload);
    }

    /**
     * 创建 RPC 响应。
     *
     * @throws NullPointerException 当标准字段为空时抛出。
     */
    public RpcResponse {
        java.util.Objects.requireNonNull(correlationId, "correlationId");
        java.util.Objects.requireNonNull(traceId, "traceId");
        java.util.Objects.requireNonNull(errorCode, "errorCode");
        errorMessage = java.util.Objects.requireNonNull(errorMessage, "errorMessage");
        payload = payload == null ? new byte[0] : payload.clone();
    }

    /**
     * 返回响应负载副本。
     *
     * @return 可变字节数组副本；可能为空数组；无序；线程安全。
     */
    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
