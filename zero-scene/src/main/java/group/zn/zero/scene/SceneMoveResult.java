package group.zn.zero.scene;

import java.util.Objects;
import java.util.Optional;

/**
 * 场景移动结果。
 *
 * <p>该结果记录移动前后的实体状态。record 不可变且线程安全；
 * `previousState` 可能为空，表示移动前实体尚未在场景中登记。
 *
 * @param previousState 移动前状态。
 * @param currentState 移动后状态。
 * @author zn
 */
public record SceneMoveResult(Optional<SceneEntityState> previousState, SceneEntityState currentState) {

    /**
     * 创建场景移动结果。
     *
     * @throws NullPointerException 当 previousState 或 currentState 为空时抛出。
     */
    public SceneMoveResult {
        previousState = Objects.requireNonNull(previousState, "previousState");
        currentState = Objects.requireNonNull(currentState, "currentState");
    }
}
