package group.zn.zero.runtime.diagnostics;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 本地与外部装配共享的安全诊断；缺配置时不伪造组件计划。
 * @param profile 当前档位。
 * @param plan 配置完整时的组件图，包含同次解析的脱敏配置。
 * @param missingConfigKeys 缺失键，排序且不可变，不包含值。
 * @author zn
 */
public record RuntimeAssemblyDiagnosis(String profile, Optional<RuntimeAssemblyPlan> plan,
        List<String> missingConfigKeys) {
    /** 校验互斥状态并冻结字段，可跨线程读取；输入非法抛 IllegalArgumentException 或 NullPointerException。 */
    public RuntimeAssemblyDiagnosis {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(plan, "plan");
        missingConfigKeys = List.copyOf(missingConfigKeys).stream().distinct().sorted().toList();
        if (plan.isPresent() == !missingConfigKeys.isEmpty()
                || plan.isPresent() && !profile.equals(plan.orElseThrow().profile())) {
            throw new IllegalArgumentException("diagnosis requires a matching plan or missing config keys");
        }
    }

    /**
     * 从完整计划创建成功诊断，不重新解析配置。
     * @param plan 已校验计划，不可为空。
     * @return 不可变诊断；无副作用，线程安全。
     * @throws NullPointerException plan 为空时抛出。
     */
    public static RuntimeAssemblyDiagnosis complete(final RuntimeAssemblyPlan plan) {
        return new RuntimeAssemblyDiagnosis(plan.profile(), Optional.of(plan), List.of());
    }
}
