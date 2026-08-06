package group.zn.zero.player;

import java.util.Objects;

/**
 * 玩家加载请求。
 *
 * <p>该请求用于把玩家在线数据加载到 player lane。record 不可变且线程安全；
 * 数据变更由 `PlayerService` 实现决定。
 *
 * @param uid 玩家 ID。
 * @param traceId 链路追踪 ID。
 * @author zn
 */
public record PlayerLoadRequest(long uid, String traceId) {

    /**
     * 创建玩家加载请求。
     *
     * @throws NullPointerException 当 traceId 为空时抛出。
     * @throws IllegalArgumentException 当 traceId 为空白时抛出。
     */
    public PlayerLoadRequest {
        traceId = Objects.requireNonNull(traceId, "traceId");
        if (traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
    }
}
