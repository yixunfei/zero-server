package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.api.BindingKey;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.config.ConfigSource;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.diagnostics.RuntimeFailurePhase;
import group.zn.zero.runtime.spi.RuntimeDeadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 显式输入的模块化 runtime 装配入口。
 *
 * <p>Builder 不执行 classpath 扫描、ServiceLoader 或环境探测。调用顺序中的 config source
 * 按高到低优先级解析；同一个 Builder 可诊断或构建多个彼此独立的 single-use runtime。
 * Builder 本身不是线程安全的；config source、decoder 和 validator 必须是只读、可重复调用的。</p>
 *
 * @author zn
 */
public final class RuntimeAssembler {

    private RuntimeAssembler() {
    }

    public static Builder builder(
            final ComponentCatalog catalog,
            final RuntimeProfile profile) {
        return new Builder(catalog, profile);
    }

    /** RuntimeAssembler builder。 */
    public static final class Builder {

        private final ComponentCatalog catalog;
        private final RuntimeProfile profile;
        private final Set<BindingKey<?>> requirements = new LinkedHashSet<>();
        private final RuntimeSelection.Builder selection = RuntimeSelection.builder();
        private final List<ConfigSource> configSources = new ArrayList<>();
        private Duration assemblyTimeout = Duration.ofSeconds(30);
        private Duration startupTimeout = Duration.ofSeconds(30);
        private LongSupplier nanoTime = System::nanoTime;
        private String presetName;

        private Builder(final ComponentCatalog catalog, final RuntimeProfile profile) {
            this.catalog = Objects.requireNonNull(catalog, "catalog");
            this.profile = Objects.requireNonNull(profile, "profile");
        }

        public Builder preset(final RuntimePreset preset) {
            RuntimePreset checked = Objects.requireNonNull(preset, "preset");
            if (presetName != null) {
                throw new IllegalStateException("runtime preset is already set");
            }
            presetName = checked.name();
            requirements.addAll(checked.requirements());
            selection.merge(checked.selection());
            return this;
        }

        public Builder require(final BindingKey<?> key) {
            requirements.add(Objects.requireNonNull(key, "key"));
            return this;
        }

        public Builder selection(final RuntimeSelection runtimeSelection) {
            selection.merge(Objects.requireNonNull(runtimeSelection, "runtimeSelection"));
            return this;
        }

        public <T> Builder select(
                final ComponentKey<T> key,
                final ComponentId providerId,
                final String sourceId) {
            selection.select(key, providerId, SelectionSource.programmatic(sourceId));
            return this;
        }

        public <T> Builder overrideSelection(
                final ComponentKey<T> key,
                final ComponentId providerId,
                final String sourceId) {
            selection.override(key, providerId, sourceId);
            return this;
        }

        public <T> Builder contribute(
                final ComponentSetKey<T> key,
                final ComponentId providerId,
                final String sourceId) {
            selection.contribute(key, providerId, SelectionSource.programmatic(sourceId));
            return this;
        }

        public Builder configSource(final ConfigSource source) {
            configSources.add(Objects.requireNonNull(source, "source"));
            return this;
        }

        /**
         * 设置 planning/config/create 阶段的累计预算。
         *
         * @param timeout 装配预算；必须大于零。
         * @return 当前 builder。
         */
        public Builder assemblyTimeout(final Duration timeout) {
            assemblyTimeout = requirePositive(timeout, "assembly timeout");
            return this;
        }

        /**
         * 设置 lifecycle start 与 mandatory startup health 的累计预算。
         *
         * <p>该预算在 {@link GameRuntime#start()} 被首次占用时开始计时，不包含 planning 和 provider create。</p>
         *
         * @param timeout 启动预算；必须大于零。
         * @return 当前 builder。
         */
        public Builder startupTimeout(final Duration timeout) {
            startupTimeout = requirePositive(timeout, "startup timeout");
            return this;
        }

        Builder monotonicClock(final LongSupplier clock) {
            nanoTime = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * 解析配置并规划组件图，但不调用 provider create 或获取 build resource。
         *
         * @return 确定性装配计划；不可为空。
         */
        public RuntimeAssemblyPlan diagnose() {
            return planned().plan();
        }

        public GameRuntime build() {
            RuntimeDeadline assemblyDeadline = RuntimeDeadline.using(assemblyTimeout, nanoTime);
            PlannedRuntime planned = planned();
            if (assemblyDeadline.expired()) {
                throw RuntimeAssemblyException.failure(
                        RuntimeErrorCode.RUNTIME_STARTUP_TIMEOUT,
                        RuntimeFailurePhase.PLANNING,
                        null,
                        "runtime=planning");
            }
            return new RuntimeBuildTransaction().build(
                    planned, assemblyDeadline, startupTimeout, nanoTime);
        }

        private Duration requirePositive(final Duration timeout, final String label) {
            Duration checked = Objects.requireNonNull(timeout, "timeout");
            if (checked.isZero() || checked.isNegative()) {
                throw new IllegalArgumentException(label + " must be positive");
            }
            return checked;
        }

        private PlannedRuntime planned() {
            return new RuntimePlanner().plan(
                    catalog,
                    selection.build(),
                    Set.copyOf(requirements),
                    profile,
                    List.copyOf(configSources),
                    Optional.ofNullable(presetName));
        }
    }
}
