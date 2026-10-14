package group.zn.zero.examples.business;

import group.zn.zero.examples.business.generated.dto.DailyRewardClaimDailyRewardProtocolDTO;
import group.zn.zero.examples.business.generated.dto.DailyRewardClaimResultProtocolDTO;
import group.zn.zero.examples.business.generated.dto.codec.DailyRewardClaimResultProtocolDTOCodec;
import group.zn.zero.examples.business.generated.protocol.ProtocolIds;
import group.zn.zero.examples.business.generated.protocol.dispatch.GeneratedProtocolDispatcher;
import group.zn.zero.game.RequestExecution;
import group.zn.zero.protocol.AsyncProtocolEndpoint;
import group.zn.zero.protocol.ProtocolFrame;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 同一 BO 的 local/TCP 帧入口；业务结果由生成胶水编码，不使用通用结果收集器。 @author zn */
public final class DailyRewardFlow {
    /** 生成的类型化请求/响应端点。 */
    private final AsyncProtocolEndpoint<DailyRewardClaimDailyRewardProtocolDTO, DailyRewardClaimResultProtocolDTO> endpoint;
    /** @param bo 共用业务实现；构造无副作用，完成后可并发共享。 */
    public DailyRewardFlow(final DailyRewardBO bo) {
        endpoint = GeneratedProtocolDispatcher.endpointDailyRewardClaimDailyRewardEventBOclaimDailyReward(bo,
                ProtocolIds.DAILY_REWARD_CLAIM_RESULT_PROTOCOL, DailyRewardClaimResultProtocolDTOCodec.INSTANCE);
    }
    /** @param frame 帧。 @param scope 已认证范围。 @return 实际完成且范围仍有效时的响应；线程安全。
     * 已过期/取消不交付成功帧，已提交库存不回滚。 */
    public CompletionStage<ProtocolFrame> handle(final ProtocolFrame frame, final RequestExecution scope) {
        try { return scope.deliver(scope.call(() -> endpoint.handle(frame))); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
}
