package group.zn.zero.rpc.observer;

/**
 * 空 RPC 传输观测器。
 *
 * @author zn
 */
enum NoopRpcTransportObserver implements RpcTransportObserver {

    /**
     * 单例。
     */
    INSTANCE;

    /**
     * 忽略观测事件。
     *
     * @param event 观测事件；可为空。
     */
    @Override
    public void onEvent(final RpcTransportEvent event) {
    }
}
