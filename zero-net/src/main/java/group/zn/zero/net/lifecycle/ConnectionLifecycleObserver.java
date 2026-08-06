package group.zn.zero.net.lifecycle;

import java.util.Objects;

/**
 * 生产连接生命周期观测端口。
 *
 * <p>框架通过调用方提供的 observer 执行器投递事件，zero-net 不直接依赖日志或监控实现。
 * 实现必须线程安全，并为下游背压、失败与降级提供明确处理。</p>
 *
 * @author zn
 */
@FunctionalInterface
public interface ConnectionLifecycleObserver {

    /**
     * 处理生命周期观测事件。
     *
     * @param observation 观测事件；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 下游记录失败时抛出绑定 ErrorCode 的异常。
     */
    void onEvent(ConnectionLifecycleObservation observation);

    /**
     * 返回无操作 observer。
     *
     * @return 无操作 observer；不可为空；线程安全。
     */
    static ConnectionLifecycleObserver noOp() {
        return observation -> Objects.requireNonNull(observation, "observation");
    }
}
