package group.zn.zero.net.lifecycle;

import java.util.Objects;

/**
 * 无操作观测端口的共享实现；稳定身份用于跳过默认路径的分配与调度。
 *
 * @author zn
 */
enum NoOpConnectionLifecycleObserver implements ConnectionLifecycleObserver {

    /** 无状态、线程安全的唯一实例。 */
    INSTANCE;

    /**
     * 校验输入后返回，不记录或修改任何状态；线程安全。
     *
     * @param observation 观测事件；不可为空。
     * @throws NullPointerException 观测事件为空时抛出。
     */
    @Override
    public void onEvent(final ConnectionLifecycleObservation observation) {
        Objects.requireNonNull(observation, "observation");
    }
}
