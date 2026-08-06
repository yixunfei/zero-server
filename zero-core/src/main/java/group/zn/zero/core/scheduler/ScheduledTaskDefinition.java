package group.zn.zero.core.scheduler;

import java.util.Objects;

/**
 * 受管定时任务定义。
 *
 * @param taskName 稳定逻辑任务名；不可包含控制字符或动态业务标识。
 * @param failurePolicy 周期任务失败后的策略；一次性任务失败后始终终止。
 * @author zn
 */
public record ScheduledTaskDefinition(
        String taskName,
        ScheduledTaskFailurePolicy failurePolicy) {

    /**
     * 最大任务名长度。
     */
    private static final int MAX_TASK_NAME_LENGTH = 128;

    /**
     * 标准化任务定义。
     *
     * @throws NullPointerException 当任务名或失败策略为空时抛出。
     * @throws IllegalArgumentException 当任务名为空白、过长或包含控制字符时抛出。
     */
    public ScheduledTaskDefinition {
        taskName = requireTaskName(taskName);
        failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
    }

    /**
     * 创建使用默认失败取消策略的任务定义。
     *
     * @param taskName 稳定逻辑任务名；不可为空白。
     * @return 任务定义；不可为空、不可变、线程安全。
     */
    public static ScheduledTaskDefinition defaults(final String taskName) {
        return new ScheduledTaskDefinition(taskName, ScheduledTaskFailurePolicy.CANCEL_ON_FAILURE);
    }

    private static String requireTaskName(final String value) {
        String current = Objects.requireNonNull(value, "taskName").trim();
        if (current.isEmpty()) {
            throw new IllegalArgumentException("taskName must not be blank");
        }
        if (current.length() > MAX_TASK_NAME_LENGTH) {
            throw new IllegalArgumentException("taskName must not exceed " + MAX_TASK_NAME_LENGTH + " characters");
        }
        if (current.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("taskName must not contain control characters");
        }
        return current;
    }
}
