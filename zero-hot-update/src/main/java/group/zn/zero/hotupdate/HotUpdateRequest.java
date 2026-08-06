package group.zn.zero.hotupdate;

import java.time.Instant;

/**
 * 热更请求。
 *
 * @param type 热更类型。
 * @param level 热更等级。
 * @param version 热更版本。
 * @param operator 操作者。
 * @param traceId 链路追踪标识。
 * @param requestedAt 请求时间。
 * @author zn
 */
public record HotUpdateRequest(
        String type,
        HotUpdateLevel level,
        String version,
        String operator,
        String traceId,
        Instant requestedAt) {

    /**
     * 创建热更请求。
     *
     * @throws NullPointerException 当标准字段为空时抛出。
     */
    public HotUpdateRequest {
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(level, "level");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(operator, "operator");
        java.util.Objects.requireNonNull(traceId, "traceId");
        java.util.Objects.requireNonNull(requestedAt, "requestedAt");
    }
}

