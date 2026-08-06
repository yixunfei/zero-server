package group.zn.zero.data.persistence;

import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * 持久化快照绑定执行器。
 *
 * <p>实现负责在绑定执行域内捕获对象快照。默认实现可以直接执行，
 * Actor 适配实现应把捕获动作派发到对应 lane，避免持久化线程直接读取 live 对象。
 *
 * @author zn
 */
public interface PersistenceBindingExecutor {

    /**
     * 在绑定执行域内捕获快照。
     *
     * @param binding 数据对象线程绑定键；不可为空。
     * @param snapshotSupplier 快照供应器；不可为空。
     * @param <T> 快照类型。
     * @return 快照结果；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 捕获失败时抛出，必须绑定 ErrorCode。
     */
    <T> CompletionStage<T> capture(DataThreadBinding binding, Supplier<T> snapshotSupplier);
}
