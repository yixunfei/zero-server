package group.zn.zero.runtime.health;

import java.util.concurrent.CompletionStage;

/**
 * 受启动器或健康管理器显式调用的异步健康探针。
 *
 * @author zn
 */
@FunctionalInterface
public interface HealthProbe {

    /**
     * 执行一次检查。
     *
     * @param request 检查请求；不可为空。
     * @return 异步结果；不可为空。
     */
    CompletionStage<HealthResult> check(HealthRequest request);
}
