package group.zn.zero.scene;

import java.util.Objects;

/**
 * 场景移动请求。
 *
 * <p>该请求用于在 scene lane 内移动玩家实体。record 不可变且线程安全。
 *
 * @param uid 玩家 ID。
 * @param sceneId 场景 ID。
 * @param position 目标坐标。
 * @param traceId 链路追踪 ID。
 * @author zn
 */
public record SceneMoveRequest(long uid, String sceneId, ScenePosition position, String traceId) {

    /**
     * 创建场景移动请求。
     *
     * @throws NullPointerException 当 sceneId、position 或 traceId 为空时抛出。
     * @throws IllegalArgumentException 当 sceneId 或 traceId 为空白时抛出。
     */
    public SceneMoveRequest {
        sceneId = requireText(sceneId, "sceneId");
        position = Objects.requireNonNull(position, "position");
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
