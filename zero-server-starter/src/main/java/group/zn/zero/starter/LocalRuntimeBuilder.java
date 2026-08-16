package group.zn.zero.starter;

import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.log.LogAppender;
import group.zn.zero.log.LogSink;
import group.zn.zero.runtime.api.BindingKey;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.assembly.ComponentCatalog;
import group.zn.zero.runtime.assembly.RuntimeAssembler;
import group.zn.zero.runtime.assembly.RuntimePreset;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.capability.RuntimeCapabilityModel;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.config.ConfigSource;
import group.zn.zero.runtime.config.ZeroConfigSource;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Local Starter 的显式 provider 装配入口。
 *
 * <p>该 builder 没有固定业务组件槽位；替换通过 typed capability 与 provider ID 表达。
 * 不执行 classpath 扫描或外部连接，成功 build 得到 single-use {@link GameRuntime}。</p>
 *
 * @author zn
 */
public final class LocalRuntimeBuilder {

    private final ZeroConfig config;
    private final LogSink terminalLogSink;
    private final LogAppender logAppender;
    private final ZeroRuntimeExecutors executors;
    private final RuntimeCapabilityModel capabilityModel;
    private final List<RuntimeComponentProvider> baseProviders;
    private final List<RuntimeComponentProvider> extensionProviders = new ArrayList<>();
    private final List<SingleSelection> overrides = new ArrayList<>();
    private final List<MultipleSelection> contributions = new ArrayList<>();
    private final Set<BindingKey<?>> requirements = new LinkedHashSet<>();
    private final List<ConfigSource> configSources = new ArrayList<>();
    private RuntimePreset preset;
    private RuntimeProfile profile;
    private Duration assemblyTimeout = Duration.ofSeconds(30);
    private Duration startupTimeout = Duration.ofSeconds(30);
    private boolean buildClaimed;

    LocalRuntimeBuilder(
            final ZeroConfig config,
            final LogSink terminalLogSink,
            final LogAppender logAppender,
            final ZeroRuntimeExecutors executors,
            final RuntimePreset preset,
            final RuntimeProfile profile) {
        this.config = Objects.requireNonNull(config, "config");
        this.terminalLogSink = Objects.requireNonNull(terminalLogSink, "terminalLogSink");
        this.logAppender = Objects.requireNonNull(logAppender, "logAppender");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.preset = Objects.requireNonNull(preset, "preset");
        this.profile = Objects.requireNonNull(profile, "profile");
        capabilityModel = StandardRuntimeCapabilityModel.instance();
        baseProviders = LocalRuntimeProviders.defaults(config, terminalLogSink, logAppender, executors);
        capabilityModel.validateDescriptors(
                baseProviders.stream().map(RuntimeComponentProvider::descriptor).toList(),
                StandardRuntimeCapabilityModel.PROFILE_LOCAL);
    }

    public ZeroConfig config() {
        return config;
    }

    public LogAppender logAppender() {
        return logAppender;
    }

    public ZeroRuntimeExecutors executors() {
        return executors;
    }

    public RuntimeCapabilityModel capabilityModel() {
        return capabilityModel;
    }

    public LocalRuntimeBuilder preset(final RuntimePreset runtimePreset, final RuntimeProfile runtimeProfile) {
        requireMutable();
        preset = Objects.requireNonNull(runtimePreset, "runtimePreset");
        profile = Objects.requireNonNull(runtimeProfile, "runtimeProfile");
        return this;
    }

    public LocalRuntimeBuilder register(final RuntimeComponentProvider provider) {
        requireMutable();
        extensionProviders.add(Objects.requireNonNull(provider, "provider"));
        return this;
    }

    public <T> LocalRuntimeBuilder override(
            final ComponentKey<T> key,
            final ComponentId providerId) {
        requireMutable();
        overrides.add(new SingleSelection(key, providerId));
        return this;
    }

    public <T> LocalRuntimeBuilder replace(
            final ComponentId providerId,
            final ComponentKey<T> key,
            final T value) {
        register(LocalRuntimeProviders.value(
                providerId,
                key,
                Objects.requireNonNull(value, "value"),
                ComponentKind.BUSINESS));
        return override(key, providerId);
    }

    public <T> LocalRuntimeBuilder replace(final ComponentKey<T> key, final T value) {
        ComponentKey<T> checkedKey = Objects.requireNonNull(key, "key");
        return replace(ComponentId.of("application." + checkedKey.id()), checkedKey, value);
    }

    public <T> LocalRuntimeBuilder contribute(
            final ComponentSetKey<T> key,
            final ComponentId providerId) {
        requireMutable();
        contributions.add(new MultipleSelection(key, providerId));
        requirements.add(key);
        return this;
    }

    public LocalRuntimeBuilder require(final BindingKey<?> key) {
        requireMutable();
        requirements.add(Objects.requireNonNull(key, "key"));
        return this;
    }

    public LocalRuntimeBuilder configSource(final ConfigSource source) {
        requireMutable();
        configSources.add(Objects.requireNonNull(source, "source"));
        return this;
    }

    /**
     * 设置 planning/config/create 阶段累计预算。
     *
     * @param timeout 装配预算；必须大于零。
     * @return 当前 builder。
     */
    public LocalRuntimeBuilder assemblyTimeout(final Duration timeout) {
        requireMutable();
        assemblyTimeout = requirePositive(timeout, "assembly timeout");
        return this;
    }

    /**
     * 设置 start/health 阶段累计预算；计时从首次调用 runtime start 开始。
     *
     * @param timeout 启动预算；必须大于零。
     * @return 当前 builder。
     */
    public LocalRuntimeBuilder startupTimeout(final Duration timeout) {
        requireMutable();
        startupTimeout = requirePositive(timeout, "startup timeout");
        return this;
    }

    public LocalRuntimeBuilder addInfrastructureLifecycle(
            final ComponentId componentId,
            final Lifecycle lifecycle) {
        register(LocalRuntimeProviders.infrastructureLifecycle(componentId, lifecycle));
        return contribute(LocalRuntimeCapabilities.INFRASTRUCTURE_LIFECYCLES, componentId);
    }

    public LocalRuntimeBuilder addApplicationLifecycle(
            final ComponentId componentId,
            final Lifecycle lifecycle) {
        register(LocalRuntimeProviders.applicationLifecycle(componentId, lifecycle));
        return contribute(LocalRuntimeCapabilities.APPLICATION_LIFECYCLES, componentId);
    }

    /**
     * 只规划并校验当前组件图，不创建 provider 或资源。
     *
     * @return 安全、确定性的装配计划。
     */
    public RuntimeAssemblyPlan diagnose() {
        requireMutable();
        return assembler().diagnose();
    }

    /**
     * 构建 single-use runtime；builder 本身也只能消费一次。
     *
     * @return 已构建但未启动的 runtime。
     */
    public GameRuntime build() {
        requireMutable();
        buildClaimed = true;
        try {
            return assembler().build();
        } catch (RuntimeException | Error failure) {
            closeExecutorsAfterFailure(failure);
            throw failure;
        }
    }

    private RuntimeAssembler.Builder assembler() {
        ComponentCatalog.Builder catalog = ComponentCatalog.builder();
        baseProviders.forEach(provider -> catalog.register("zero.local", provider));
        extensionProviders.forEach(provider -> catalog.register("application", provider));
        RuntimeAssembler.Builder assembler = RuntimeAssembler.builder(catalog.build(), profile)
                .preset(preset)
                .assemblyTimeout(assemblyTimeout)
                .startupTimeout(startupTimeout);
        configSources.forEach(assembler::configSource);
        assembler.configSource(new ZeroConfigSource("zero.local", config));
        requirements.forEach(assembler::require);
        overrides.forEach(selection -> applyOverride(assembler, selection));
        contributions.forEach(selection -> applyContribution(assembler, selection));
        return assembler;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyOverride(final RuntimeAssembler.Builder assembler, final SingleSelection selection) {
        assembler.overrideSelection(
                (ComponentKey) selection.key(),
                selection.providerId(),
                "local-builder");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyContribution(final RuntimeAssembler.Builder assembler, final MultipleSelection selection) {
        assembler.contribute(
                (ComponentSetKey) selection.key(),
                selection.providerId(),
                "local-builder");
    }

    private void requireMutable() {
        if (buildClaimed) {
            throw new IllegalStateException("local runtime builder has already been consumed");
        }
    }

    private Duration requirePositive(final Duration timeout, final String label) {
        Duration checked = Objects.requireNonNull(timeout, "timeout");
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return checked;
    }

    private void closeExecutorsAfterFailure(final Throwable failure) {
        try {
            executors.close();
        } catch (RuntimeException | Error closeFailure) {
            if (closeFailure != failure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private record SingleSelection(ComponentKey<?> key, ComponentId providerId) {
        private SingleSelection {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(providerId, "providerId");
        }
    }

    private record MultipleSelection(ComponentSetKey<?> key, ComponentId providerId) {
        private MultipleSelection {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(providerId, "providerId");
        }
    }
}
