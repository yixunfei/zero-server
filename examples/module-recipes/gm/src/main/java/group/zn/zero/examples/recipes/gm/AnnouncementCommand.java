package group.zn.zero.examples.recipes.gm;

import group.zn.zero.gm.GmCommandExecutionRequest;
import group.zn.zero.gm.GmCommandExecutionResult;
import group.zn.zero.gm.GmCommandHandler;
import group.zn.zero.gm.GmDryRunResult;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 公告发布示例处理器：只修改本实例计数，不向玩家或外部系统发送消息。
 * 线程安全；真实业务应在 execute 中接入受管服务和持久化业务幂等。
 * @author zn
 */
final class AnnouncementCommand implements GmCommandHandler {
    /** 本次示例实际执行次数，便于确认重放不重复提交。 */
    private final AtomicInteger executions = new AtomicInteger();

    /**
     * 只预览公告数量，不修改状态；线程安全。
     * @param request 经过解析和授权的非空请求。
     * @return 非空不可变预演结果，数据 Map 无序且不可变。
     */
    @Override
    public GmDryRunResult dryRun(final GmCommandExecutionRequest request) {
        return GmDryRunResult.accepted(request.commandKey(), "将发布一条示例公告", Map.of("count", "1"));
    }

    /**
     * 增加本地公告计数；线程安全，只有 endpoint 授权通过后才会进入。
     * @param request 经过解析和授权的非空请求。
     * @return 非空不可变执行结果，数据 Map 无序且不可变。
     */
    @Override
    public GmCommandExecutionResult execute(final GmCommandExecutionRequest request) {
        int count = executions.incrementAndGet();
        return GmCommandExecutionResult.success(request.commandKey(), "示例公告已记录",
                Map.of("count", Integer.toString(count), "internalNote", "仅限服务端可见"));
    }

    /** @return 已执行次数；线程安全的只读快照，不修改状态。 */
    int executions() { return executions.get(); }
}
