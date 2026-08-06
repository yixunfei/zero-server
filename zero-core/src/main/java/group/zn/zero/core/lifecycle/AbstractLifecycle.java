package group.zn.zero.core.lifecycle;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于原子状态的生命周期基类。
 *
 * @author zn
 */
public abstract class AbstractLifecycle implements Lifecycle {

    /**
     * 运行状态。
     */
    private final AtomicReference<LifecycleState> state = new AtomicReference<>(LifecycleState.NEW);

    /**
     * 启动组件。
     *
     * @throws group.zn.zero.core.error.ZeroException 启动失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    public final synchronized void start() {
        LifecycleState current = state.get();
        beforeStartRequest();
        if (current == LifecycleState.RUNNING || current == LifecycleState.STARTING) {
            return;
        }
        if (current == LifecycleState.STOPPING) {
            throw ZeroException.of(SystemErrorCode.SYSTEM_ERROR, "Lifecycle is stopping and cannot start", null);
        }
        state.set(LifecycleState.STARTING);
        try {
            doStart();
            state.set(LifecycleState.RUNNING);
        } catch (RuntimeException | Error ex) {
            state.set(LifecycleState.FAILED);
            throw asZeroException(ex, "Lifecycle start failed");
        }
    }

    /**
     * 停止组件。
     *
     * @throws group.zn.zero.core.error.ZeroException 停止失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    public final synchronized void stop() {
        LifecycleState current = state.get();
        if (current == LifecycleState.NEW || current == LifecycleState.STOPPED) {
            state.set(LifecycleState.STOPPED);
            return;
        }
        if (current == LifecycleState.STOPPING) {
            return;
        }
        if (current == LifecycleState.STARTING) {
            throw ZeroException.of(SystemErrorCode.SYSTEM_ERROR, "Lifecycle is starting and cannot stop", null);
        }
        state.set(LifecycleState.STOPPING);
        try {
            doStop();
            state.set(LifecycleState.STOPPED);
        } catch (RuntimeException | Error ex) {
            state.set(LifecycleState.FAILED);
            throw asZeroException(ex, "Lifecycle stop failed");
        }
    }

    /**
     * 返回当前生命周期状态。
     *
     * @return 生命周期状态；不可为空；线程安全。
     */
    @Override
    public final LifecycleState state() {
        return state.get();
    }

    /**
     * 执行真实启动逻辑。
     *
     * @throws group.zn.zero.core.error.ZeroException 启动失败时抛出，必须绑定 ErrorCode。
     */
    protected void doStart() {
        // 默认无操作，子类按需覆盖。
    }

    /**
     * 校验一次公开启动请求；默认不改变普通生命周期的重复启动幂等语义。
     *
     * <p>线程安全：始终在 {@link #start()} 的实例监视器内调用。数据变更与异常策略由子类声明；
     * 该钩子在任何状态短路之前执行，适合实现 single-use 等更严格契约。</p>
     *
     * @throws group.zn.zero.core.error.ZeroException 子类拒绝本次启动请求时抛出。
     */
    protected void beforeStartRequest() {
        // 默认无操作，子类可在不破坏通用幂等语义的前提下收紧启动契约。
    }

    /**
     * 执行真实停止逻辑。
     *
     * @throws group.zn.zero.core.error.ZeroException 停止失败时抛出，必须绑定 ErrorCode。
     */
    protected void doStop() {
        // 默认无操作，子类按需覆盖。
    }

    private ZeroException asZeroException(final Throwable throwable, final String defaultMessage) {
        if (throwable instanceof ZeroException zeroException) {
            return zeroException;
        }
        String message = throwable.getMessage() == null ? defaultMessage : throwable.getMessage();
        return ZeroException.of(SystemErrorCode.SYSTEM_ERROR, message, throwable);
    }
}
