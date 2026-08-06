package group.zn.zero.scene;

import java.util.Optional;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 场景基础服务。
 *
 * <p>该接口承载阶段 3 首版可直接接入的进入场景、移动和查询能力。
 * 实现必须保证场景实体状态在 scene lane 内修改。
 *
 * @author zn
 */
public interface SceneService {

    /**
     * 进入场景。
     *
     * <p>数据变更：在 scene lane 内创建或替换玩家实体状态。
     *
     * @param request 进入场景请求；不可为空。
     * @return 场景实体状态；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    CompletionStage<SceneEntityState> enterScene(SceneEnterRequest request);

    /**
     * 移动场景实体。
     *
     * <p>数据变更：在 scene lane 内替换玩家实体坐标。
     *
     * @param request 移动请求；不可为空。
     * @return 场景实体状态；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    CompletionStage<SceneEntityState> move(SceneMoveRequest request);

    /**
     * 移动场景实体并返回移动结果。
     *
     * <p>数据变更：在 scene lane 内替换玩家实体坐标。
     *
     * @param request 移动请求；不可为空。
     * @return 场景移动结果；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    CompletionStage<SceneMoveResult> moveWithResult(SceneMoveRequest request);

    /**
     * 离开场景。
     *
     * <p>数据变更：在 scene lane 内移除玩家实体状态。
     *
     * @param request 离开场景请求；不可为空。
     * @return 离开场景结果；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    CompletionStage<SceneLeaveResult> leaveScene(SceneLeaveRequest request);

    /**
     * 查询场景实体状态。
     *
     * <p>数据变更：无。返回 Optional 可能为空；Optional 本身不可变、无序且线程安全。
     *
     * @param sceneId 场景 ID；不可为空。
     * @param uid 玩家 ID。
     * @param traceId 链路追踪 ID；不可为空。
     * @return 场景实体状态 Optional；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    CompletionStage<Optional<SceneEntityState>> queryEntity(String sceneId, long uid, String traceId);

    /**
     * 查询场景实体列表。
     *
     * <p>数据变更：无。返回列表不可为空、可能为空、无序、不可变且线程安全。
     *
     * @param sceneId 场景 ID；不可为空。
     * @param traceId 链路追踪 ID；不可为空。
     * @return 场景实体状态快照；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    CompletionStage<List<SceneEntityState>> listEntities(String sceneId, String traceId);
}
