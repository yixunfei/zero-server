package group.zn.zero.rpc.observer;

/**
 * RPC 传输观测器。
 *
 * <p>实现方不得在回调中执行不可控阻塞逻辑。传输实现会隔离 observer 异常，但生产接入仍应把
 * 日志、指标和告警写入异步或低成本路径。
 *
 * @author zn
 */
@FunctionalInterface
public interface RpcTransportObserver {

    /**
     * 处理传输观测事件。
     *
     * @param event 观测事件；不可为空。
     */
    void onEvent(RpcTransportEvent event);

    /**
     * 返回空观测器。
     *
     * @return 空观测器；不可为空；线程安全。
     */
    static RpcTransportObserver noop() {
        return NoopRpcTransportObserver.INSTANCE;
    }
}
