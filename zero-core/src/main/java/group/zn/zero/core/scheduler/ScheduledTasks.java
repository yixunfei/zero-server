package group.zn.zero.core.scheduler;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 同步业务动作到异步定时任务契约的便捷适配器。
 *
 * <p>本类不创建线程、不切换执行器，也不会等待远程 IO。传入动作由 scheduler 受管业务执行域调用。
 *
 * @author zn
 */
public final class ScheduledTasks {

    private ScheduledTasks() {
    }

    /**
     * 把同步无上下文动作适配为受管任务。
     *
     * @param action 同步动作；不可为空。
     * @return 受管任务；不可为空；线程安全性由传入动作决定。
     * @throws NullPointerException 当同步动作为空时抛出。
     */
    public static ScheduledTask runnable(final Runnable action) {
        Runnable checkedAction = Objects.requireNonNull(action, "action");
        return context -> {
            checkedAction.run();
            return CompletableFuture.completedFuture(null);
        };
    }

    /**
     * 把同步上下文动作适配为受管任务。
     *
     * @param action 同步上下文动作；不可为空。
     * @return 受管任务；不可为空；线程安全性由传入动作决定。
     * @throws NullPointerException 当同步动作为空时抛出。
     */
    public static ScheduledTask consumer(final Consumer<ScheduledTaskContext> action) {
        Consumer<ScheduledTaskContext> checkedAction = Objects.requireNonNull(action, "action");
        return context -> {
            checkedAction.accept(context);
            return CompletableFuture.completedFuture(null);
        };
    }
}
