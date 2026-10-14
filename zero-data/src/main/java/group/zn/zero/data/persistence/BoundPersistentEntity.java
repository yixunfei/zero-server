package group.zn.zero.data.persistence;

import group.zn.zero.data.model.VersionedEntity;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.UnaryOperator;

/**
 * 绑定执行域内的不可变业务聚合持有者；更新仅代表内存接受，由 PersistenceManager 周期保存。
 * 保存确认只推进版本，绝不以旧快照覆盖期间的新业务状态；所有状态访问都经过同一 binding executor。
 * 不适用于必须等待 CAS 后确认的奖励/扣款，应对这些业务直接使用 Repository 条件提交。
 * @param <ID> 主键类型。 @param <T> 不可变聚合类型。 @author zn
 */
public final class BoundPersistentEntity<ID, T extends VersionedEntity<ID>> {
    /** 所属执行域。 */
    private final DataThreadBinding binding;
    /** 绑定执行器；必须与持久化管理器共用。 */
    private final PersistenceBindingExecutor executor;
    /** 脏对象管理器。 */
    private final PersistenceManager persistence;
    /** 已注册目标名。 */
    private final String target;
    /** 仅在绑定执行域内读取/替换。 */
    private T current;

    /** @param initial 已加载的不可变聚合。 @param target 已注册持久化目标。 @param binding 非 unbound 执行域。
     * @param executor 与管理器共用的绑定执行器。 @param persistence 管理器。
     * 构造后安全发布，不创建线程；不可并发用另一持有者修改同一对象。
     * @throws IllegalArgumentException 使用未绑定执行域。 */
    public BoundPersistentEntity(final T initial, final String target, final DataThreadBinding binding,
            final PersistenceBindingExecutor executor, final PersistenceManager persistence) {
        this.current = Objects.requireNonNull(initial); this.target = Objects.requireNonNull(target);
        this.binding = Objects.requireNonNull(binding); this.executor = Objects.requireNonNull(executor);
        this.persistence = Objects.requireNonNull(persistence);
        if (binding.isUnbound()) throw new IllegalArgumentException("persistent entity requires a serial binding");
    }

    /** 在绑定 lane 修改并标脏；不允许业务更改主键/版本。先完成脏目录准入才修改状态。
     * @param mutation 短时纯函数，返回不可变新状态。 @return 内存接受结果；不代表耐久提交。
     * @throws RuntimeException 函数或调度同步失败；其他失败通过阶段传播；线程安全。 */
    public CompletionStage<T> update(final UnaryOperator<T> mutation) {
        Objects.requireNonNull(mutation);
        return executor.capture(binding, () -> {
            T next = Objects.requireNonNull(mutation.apply(current), "new state");
            if (!current.id().equals(next.id()) || current.version() != next.version()) {
                throw new IllegalArgumentException("mutation must preserve identity and confirmed version");
            }
            persistence.markDirty(target, current.id(), binding, () -> current, saved -> confirm(saved.version()));
            current = next;
            return current;
        });
    }

    /** @return 绑定域内捕获的不可变当前状态；只读、线程安全，返回阶段不可为空。 */
    public CompletionStage<T> snapshot() { return executor.capture(binding, () -> current); }

    @SuppressWarnings("unchecked")
    private void confirm(final long version) {
        if (version < current.version()) throw new IllegalStateException("confirmed version must not regress");
        current = (T) current.withVersion(version);
    }
}
