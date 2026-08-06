package group.zn.zero.scene;

import java.util.Objects;

/**
 * 场景实体状态。
 *
 * <p>该状态记录玩家实体所在场景与坐标。record 不可变且线程安全；
 * 状态替换应在 scene lane 内完成。
 *
 * @param uid 玩家 ID。
 * @param sceneId 场景 ID。
 * @param position 场景坐标。
 * @author zn
 */
public record SceneEntityState(long uid, String sceneId, ScenePosition position) {

    /**
     * 创建场景实体状态。
     *
     * @throws NullPointerException 当 sceneId 或 position 为空时抛出。
     * @throws IllegalArgumentException 当 sceneId 为空白时抛出。
     */
    public SceneEntityState {
        sceneId = Objects.requireNonNull(sceneId, "sceneId");
        if (sceneId.isBlank()) {
            throw new IllegalArgumentException("sceneId must not be blank");
        }
        position = Objects.requireNonNull(position, "position");
    }
}
