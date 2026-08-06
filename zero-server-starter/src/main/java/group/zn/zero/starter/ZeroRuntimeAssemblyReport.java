package group.zn.zero.starter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * starter 运行时装配诊断报告。
 *
 * <p>该报告只暴露组件实现类型和生命周期顺序，不暴露连接串、账号、密码、token
 * 或其他敏感配置值。用于启动日志、测试断言和本地排障。
 *
 * @param mode 运行模式。
 * @param name 应用名称。
 * @param componentTypes 组件槽位到实现类名的映射。
 * @param lifecycleComponentTypes 生命周期组件启动顺序。
 * @author zn
 */
public record ZeroRuntimeAssemblyReport(
        String mode,
        String name,
        Map<String, String> componentTypes,
        List<String> lifecycleComponentTypes) {

    /**
     * 创建 starter 装配诊断报告。
     *
     * @throws NullPointerException 当标准字段为空时抛出。
     * @throws IllegalArgumentException 当运行模式或应用名称为空白时抛出。
     */
    public ZeroRuntimeAssemblyReport {
        mode = requireText(mode, "mode");
        name = requireText(name, "name");
        componentTypes = Map.copyOf(Objects.requireNonNull(componentTypes, "componentTypes"));
        lifecycleComponentTypes = List.copyOf(Objects.requireNonNull(
                lifecycleComponentTypes,
                "lifecycleComponentTypes"));
    }

    /**
     * 从运行时组件生成诊断报告。
     *
     * @param components 运行时组件；不可为空。
     * @return 装配诊断报告；不可为空；不包含敏感配置值。
     * @throws NullPointerException 当运行时组件为空时抛出。
     */
    public static ZeroRuntimeAssemblyReport from(final ZeroRuntimeComponents components) {
        ZeroRuntimeComponents current = Objects.requireNonNull(components, "components");
        Map<String, String> types = new LinkedHashMap<>();
        types.put("config", typeName(current.config()));
        types.put("eventBus", typeName(current.eventBus()));
        types.put("deadLetterSink", typeName(current.deadLetterSink()));
        types.put("actorScheduler", typeName(current.actorScheduler()));
        types.put("protocolRegistry", typeName(current.protocolRegistry()));
        types.put("rpcTransport", typeName(current.rpcTransport()));
        types.put("rpcHandlerRegistry", typeName(current.rpcHandlerRegistry()));
        types.put("persistenceManager", typeName(current.persistenceManager()));
        types.put("cacheService", typeName(current.cacheService()));
        types.put("logAppender", typeName(current.logAppender()));
        types.put("monitorRuntime", typeName(current.monitorRuntime()));
        types.put("executors", typeName(current.executors()));
        List<String> lifecycleTypes = current.lifecycleComponents().stream()
                .map(ZeroRuntimeAssemblyReport::typeName)
                .toList();
        return new ZeroRuntimeAssemblyReport(
                current.config().getOrDefault(ZeroRuntimeConfigKeys.ZERO_MODE, ZeroRuntimeConfigKeys.MODE_LOCAL),
                current.config().getOrDefault(ZeroRuntimeConfigKeys.ZERO_NAME, ZeroRuntimeConfigKeys.DEFAULT_NAME),
                types,
                lifecycleTypes);
    }

    /**
     * 返回指定组件槽位的实现类名。
     *
     * @param slot 组件槽位；不可为空。
     * @return 实现类名；为空表示报告中没有该槽位；线程安全。
     * @throws NullPointerException 当组件槽位为空时抛出。
     */
    public Optional<String> componentType(final String slot) {
        return Optional.ofNullable(componentTypes.get(Objects.requireNonNull(slot, "slot")));
    }

    private static String typeName(final Object component) {
        return component.getClass().getName();
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
