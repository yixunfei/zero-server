package group.zn.zero.core.lifecycle;

/**
 * 框架组件生命周期接口。
 *
 * @author zn
 */
public interface Lifecycle {

    /**
     * 返回当前生命周期状态。
     *
     * @return 生命周期状态；不可为空；线程安全性由实现声明。
     */
    LifecycleState state();

    /**
     * 启动组件。
     *
     * @throws group.zn.zero.core.error.ZeroException 启动失败时抛出，必须绑定 ErrorCode。
     */
    void start();

    /**
     * 停止组件。
     *
     * @throws group.zn.zero.core.error.ZeroException 停止失败时抛出，必须绑定 ErrorCode。
     */
    void stop();

    /**
     * 返回组件是否正在运行。
     *
     * @return true 表示运行中；线程安全。
     */
    default boolean running() {
        return state() == LifecycleState.RUNNING;
    }
}
