package group.zn.zero.runtime.assembly;

import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.runtime.api.BindingKey;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentCreationContext;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.List;
import java.util.Objects;

/** 单值应用组件的显式定义；规划不执行工厂，创建出的资源由 runtime 管理。 */
public final class RuntimeComponentBuilder<T> {
    /** 组件提供的类型化能力。 */
    private final ComponentKey<T> key;
    /** 可变描述；builder 仅限装配线程使用。 */
    private final ComponentDescriptor.Builder descriptor;

    RuntimeComponentBuilder(final ComponentId id, final ComponentKey<T> key) {
        this.key = Objects.requireNonNull(key, "key");
        descriptor = ComponentDescriptor.builder(id).provide(key).kind(ComponentKind.BUSINESS);
    }

    /**
     * 声明创建所需的能力；缺失依赖在规划阶段拒绝。
     * @param dependencies 必需的单值或多值能力，可为空数组。
     * @return 当前非线程安全 builder。
     * @throws NullPointerException 数组或能力为空。
     */
    public RuntimeComponentBuilder<T> require(final BindingKey<?>... dependencies) {
        for (BindingKey<?> dependency : Objects.requireNonNull(dependencies, "dependencies")) descriptor.require(dependency);
        return this;
    }

    /**
     * 冻结定义并选择根能力；此处不执行 factory。Lifecycle 自动参与 start/stop；AutoCloseable
     * 创建后立即登记，后续创建失败也会回滚关闭。外部共享对象应使用 RuntimeModule.value。
     * @param factory 延迟工厂，不可为空；中间资源应立即登记到 context.resources()。
     * @return 不可变模块；可跨线程读取，工厂由每次 runtime build 独立调用。
     * @throws NullPointerException 工厂为空。
     */
    public RuntimeModule create(final Factory<? extends T> factory) {
        Objects.requireNonNull(factory, "factory");
        ComponentDescriptor frozen = descriptor.build();
        var provider = RuntimeProviders.create(frozen, context -> {
            T value = Objects.requireNonNull(factory.create(context), "component factory result");
            if (value instanceof AutoCloseable resource) context.resources().register(resource);
            var contribution = ComponentContribution.builder().bind(key, value);
            if (value instanceof Lifecycle lifecycle) contribution.lifecycle(lifecycle);
            return contribution.build();
        });
        return RuntimeModule.of(frozen.id().value(), List.of(provider), key);
    }

    /** 在 build 阶段执行的单值工厂；依赖只通过已声明的 context 能力访问。 */
    @FunctionalInterface
    public interface Factory<T> {
        /** 创建新实例；失败抛出异常，已登记资源由 runtime 回滚。 */
        T create(ComponentCreationContext context) throws Exception;
    }
}
