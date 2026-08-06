package group.zn.zero.starter.production;

import group.zn.zero.starter.ZeroRuntimeAssemblyReport;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 生产 runtime 装配诊断报告。
 *
 * <p>报告只包含 profile、组件类型、Adapter 状态、配置键名和来源类型，不包含任何敏感配置值。
 *
 * @param profile runtime profile。
 * @param name 固定脱敏 runtime 名称；构造时不会保留调用方原值。
 * @param runtimeReport 基础 starter 装配报告；构建前诊断时可为空。
 * @param adapterStatuses Adapter 状态快照；不可变、有序、可能为空、线程安全。
 * @param lifecycleComponentTypes 生命周期组件类型；不可变、有序、可能为空、线程安全。
 * @param warnings 警告信息；不可变、有序、可能为空、线程安全。
 * @author zn
 */
public record ZeroProductionAssemblyReport(
        String profile,
        String name,
        ZeroRuntimeAssemblyReport runtimeReport,
        Map<String, ZeroProductionAdapterStatus> adapterStatuses,
        List<String> lifecycleComponentTypes,
        List<String> warnings) {

    /** production 报告固定使用的脱敏 runtime 名称。 */
    public static final String REDACTED_RUNTIME_NAME = "<redacted>";

    /**
     * 创建生产 runtime 装配诊断报告。
     *
     * @throws NullPointerException 当必填字段为空时抛出。
     * @throws IllegalArgumentException 当 profile 或名称为空白时抛出。
     */
    public ZeroProductionAssemblyReport {
        profile = requireText(profile, "profile");
        requireText(name, "name");
        name = REDACTED_RUNTIME_NAME;
        runtimeReport = redactRuntimeReport(runtimeReport);
        adapterStatuses = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(
                adapterStatuses,
                "adapterStatuses")));
        lifecycleComponentTypes = List.copyOf(Objects.requireNonNull(
                lifecycleComponentTypes,
                "lifecycleComponentTypes"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    /**
     * 查询指定 Adapter 状态。
     *
     * @param adapterName Adapter 名称；不可为空。
     * @return Adapter 状态；为空表示报告中无该 Adapter，线程安全。
     * @throws NullPointerException Adapter 名称为空时抛出。
     */
    public Optional<ZeroProductionAdapterStatus> adapterStatus(final String adapterName) {
        return Optional.ofNullable(adapterStatuses.get(Objects.requireNonNull(adapterName, "adapterName")));
    }

    /**
     * 返回所有缺失配置键。
     *
     * @return 不可变、有序、可能为空、线程安全的缺失配置键列表。
     */
    public List<String> missingConfigKeys() {
        return adapterStatuses.values().stream()
                .flatMap(status -> status.missingConfigKeys().stream())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 判断报告文本是否包含指定片段。
     *
     * <p>该方法只用于测试和本地诊断，生产代码不应依赖全文搜索做安全判断。
     *
     * @param fragment 待查找片段；不可为空。
     * @return true 表示报告字符串包含该片段；线程安全。
     * @throws NullPointerException 待查找片段为空时抛出。
     */
    public boolean containsFragment(final String fragment) {
        return toString().contains(Objects.requireNonNull(fragment, "fragment"));
    }

    /**
     * 创建不含配置 runtime name 的 starter 报告副本。
     *
     * @param report starter 原始报告；构建前诊断时可为空，不会被保留。
     * @return 固定脱敏名称的报告副本；输入为空时返回空。
     */
    private static ZeroRuntimeAssemblyReport redactRuntimeReport(
            final ZeroRuntimeAssemblyReport report) {
        if (report == null) {
            return null;
        }
        return new ZeroRuntimeAssemblyReport(
                report.mode(),
                REDACTED_RUNTIME_NAME,
                report.componentTypes(),
                report.lifecycleComponentTypes());
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
