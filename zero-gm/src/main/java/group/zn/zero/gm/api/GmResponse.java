package group.zn.zero.gm.api;

/**
 * GM 统一响应结构。
 *
 * @param code 响应码。
 * @param msg 响应说明。
 * @param data 响应数据。
 * @param traceId 链路追踪标识。
 * @param <T> 响应数据类型。
 * @author zn
 */
public record GmResponse<T>(String code, String msg, T data, String traceId) {

    /**
     * 创建 GM 响应。
     *
     * @throws NullPointerException 当响应码、说明或 traceId 为空时抛出。
     */
    public GmResponse {
        java.util.Objects.requireNonNull(code, "code");
        java.util.Objects.requireNonNull(msg, "msg");
        java.util.Objects.requireNonNull(traceId, "traceId");
    }
}

