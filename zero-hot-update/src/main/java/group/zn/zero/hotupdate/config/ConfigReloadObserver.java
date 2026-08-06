package group.zn.zero.hotupdate.config;

import group.zn.zero.hotupdate.HotUpdateRequest;

/**
 * 配置表加载与重载观察者。
 *
 * <p>回调在配置 IO 执行域中同步执行。实现不得直接修改玩家、场景或其他 Actor 绑定状态；
 * 如需触发业务行为，必须投递到对应 Actor。观察者异常会绑定 ErrorCode 并使当前异步任务失败，
 * 不会被静默吞掉。
 *
 * @author zn
 */
@FunctionalInterface
public interface ConfigReloadObserver {

    /**
     * 消费一次加载或重载结果。
     *
     * @param request 热更请求上下文；不可为空。
     * @param result 重载结果；不可为空、不可变。
     * @throws RuntimeException 当观察或日志落地失败时抛出；调用方会绑定 observer ErrorCode。
     */
    void onReload(HotUpdateRequest request, ConfigReloadResult result);

    /**
     * 返回无操作观察者。
     *
     * @return 无操作观察者；不可为空；线程安全。
     */
    static ConfigReloadObserver noOp() {
        return (request, result) -> {
            // 显式无操作观察者，用于不需要日志的测试或嵌入场景。
        };
    }
}
