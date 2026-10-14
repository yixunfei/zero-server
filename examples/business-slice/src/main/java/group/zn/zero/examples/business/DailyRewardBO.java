package group.zn.zero.examples.business;

import group.zn.zero.actor.LaneKey;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.examples.business.generated.bo.DailyRewardClaimDailyRewardEventBO;
import group.zn.zero.examples.business.generated.dto.DailyRewardClaimDailyRewardProtocolDTO;
import group.zn.zero.examples.business.generated.dto.DailyRewardClaimResultProtocolDTO;
import group.zn.zero.game.ActorRequestExecutor;
import group.zn.zero.game.RequestExecution;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** local 与 TCP 共用的类型化异步 BO；只信任认证主体，不以 DTO uid/trace 提升权限。 @author zn */
public final class DailyRewardBO implements DailyRewardClaimDailyRewardEventBO.Async<DailyRewardClaimResultProtocolDTO> {
    /** 玩家执行域入口。 */
    private final ActorRequestExecutor actors;
    /** 原子奖励领域服务。 */
    private final DailyRewardService service;
    /** @param actors Actor 入口。 @param service 领域服务；依赖须线程安全，构造无副作用。 */
    public DailyRewardBO(final ActorRequestExecutor actors, final DailyRewardService service) {
        this.actors = Objects.requireNonNull(actors); this.service = Objects.requireNonNull(service);
    }

    /** @param request 请求。 @return CAS 及通知流程完成后的类型化响应；不阻塞。
     * @throws ZeroException 身份缺失、UID 不符或权限不足。 */
    @Override public CompletionStage<DailyRewardClaimResultProtocolDTO> claimDailyReward(
            final DailyRewardClaimDailyRewardProtocolDTO request) {
        RequestExecution scope = RequestExecution.current().orElseThrow(() -> ZeroException.of(SystemErrorCode.INVALID_ARGUMENT));
        var identity = scope.identity();
        if (!identity.subject().equals(Long.toString(request.uid)) || !identity.allows("reward:claim")) {
            throw ZeroException.of(SystemErrorCode.INVALID_ARGUMENT, "authenticated player mismatch", null);
        }
        return actors.submit(LaneKey.player(identity.subject()), () -> service.claim(request.uid, request.idempotencyKey,
                request.itemId, request.quantity, identity.traceId())).thenApply(result -> {
                    var response = new DailyRewardClaimResultProtocolDTO();
                    response.status = result.status().name(); response.idempotencyKey = result.idempotencyKey();
                    response.itemId = result.itemId(); response.quantity = result.quantity(); response.traceId = identity.traceId();
                    return response;
                });
    }
}
