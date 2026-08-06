package group.zn.zero.scene;

import java.util.Objects;
import java.util.Optional;

/**
 * 离开场景结果。
 *
 * <p>该结果记录实体离开前的状态。record 不可变且线程安全；
 * `leftState` 可能为空，表示实体原本不在该场景中。
 *
 * @param sceneId 场景 ID。
 * @param uid 玩家 ID。
 * @param removed 是否实际移除。
 * @param leftState 离开前状态。
 * @author zn
 */
public record SceneLeaveResult(
        String sceneId,
        long uid,
        boolean removed,
        Optional<SceneEntityState> leftState) {

    /**
     * 创建离开场景结果。
     *
     * @throws NullPointerException 当 sceneId 或 leftState 为空时抛出。
     * @throws IllegalArgumentException 当 sceneId 为空白时抛出。
     */
    public SceneLeaveResult {
        sceneId = Objects.requireNonNull(sceneId, "sceneId");
        if (sceneId.isBlank()) {
            throw new IllegalArgumentException("sceneId must not be blank");
        }
        leftState = Objects.requireNonNull(leftState, "leftState");
    }
}
