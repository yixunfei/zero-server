package group.zn.zero.scene;

import java.util.Objects;

/**
 * 离开场景请求。
 *
 * <p>该请求用于在 scene lane 内移除玩家实体。record 不可变且线程安全。
 *
 * @param uid 玩家 ID。
 * @param sceneId 场景 ID。
 * @param traceId 链路追踪 ID。
 * @author zn
 */
public record SceneLeaveRequest(long uid, String sceneId, String traceId) {

    /**
     * 创建离开场景请求。
     *
     * @throws NullPointerException 当 sceneId 或 traceId 为空时抛出。
     * @throws IllegalArgumentException 当 sceneId 或 traceId 为空白时抛出。
     */
    public SceneLeaveRequest {
        sceneId = requireText(sceneId, "sceneId");
        traceId = requireText(traceId, "traceId");
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
