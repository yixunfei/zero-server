package group.zn.zero.data.persistence;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * 直接执行的持久化快照绑定执行器。
 *
 * <p>该实现用于本地原型和单元测试。生产环境中，绑定到玩家、场景或实体的对象应由
 * actor/starter 适配实现把捕获动作转发到对应执行域。
 *
 * @author zn
 */
public final class DirectPersistenceBindingExecutor implements PersistenceBindingExecutor {

    /**
     * 创建直接执行的持久化快照绑定执行器。
     */
    public DirectPersistenceBindingExecutor() {
    }

    /**
     * 在当前调用线程捕获快照。
     *
     * @param binding 数据对象线程绑定键；不可为空。
     * @param snapshotSupplier 快照供应器；不可为空。
     * @param <T> 快照类型。
     * @return 快照结果；不可为空；线程安全性取决于调用方传入的供应器。
     */
    @Override
    public <T> CompletionStage<T> capture(
            final DataThreadBinding binding,
            final Supplier<T> snapshotSupplier) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        try {
            return CompletableFuture.completedFuture(snapshotSupplier.get());
        } catch (RuntimeException ex) {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(ZeroException.of(
                    DataErrorCode.PERSISTENCE_FLUSH_FAILED,
                    "capture persistence snapshot failed",
                    ex));
            return future;
        }
    }
}
