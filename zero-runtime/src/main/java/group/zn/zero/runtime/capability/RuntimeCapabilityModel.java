package group.zn.zero.runtime.capability;

import group.zn.zero.runtime.api.BindingKey;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.internal.RuntimeIdentifiers;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 模块选择器、项目生成器和 Starter catalog 共享的只读逻辑能力模型。
 *
 * <p>模型不持有 Java 实现类型，不创建组件，也不根据 classpath 或环境补全 provider。</p>
 *
 * @author zn
 */
public final class RuntimeCapabilityModel {

    private final String id;
    private final Map<String, RuntimeCapability> capabilities;
    private final Map<ComponentId, RuntimeProviderCapability> providers;

    private RuntimeCapabilityModel(final Builder builder) {
        id = builder.id;
        capabilities = Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(builder.capabilities)));
        providers = Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(builder.providers)));
        validateReferences();
        validateAcyclicDependencies();
    }

    public static Builder builder(final String id) {
        return new Builder(id);
    }

    public String id() {
        return id;
    }

    public List<RuntimeCapability> capabilities() {
        return List.copyOf(capabilities.values());
    }

    public List<RuntimeProviderCapability> providers() {
        return List.copyOf(providers.values());
    }

    public Optional<RuntimeCapability> capability(final String capabilityId) {
        return Optional.ofNullable(capabilities.get(RuntimeIdentifiers.requireStableId(
                capabilityId, "capabilityId")));
    }

    public Optional<RuntimeProviderCapability> provider(final ComponentId providerId) {
        return Optional.ofNullable(providers.get(Objects.requireNonNull(providerId, "providerId")));
    }

    /**
     * 返回根能力及其逻辑依赖闭包，按 ID 稳定排序。
     *
     * @param rootCapabilityIds 根能力 ID。
     * @return 不可变能力列表。
     */
    public List<RuntimeCapability> closure(final Collection<String> rootCapabilityIds) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        Objects.requireNonNull(rootCapabilityIds, "rootCapabilityIds").stream()
                .map(value -> RuntimeIdentifiers.requireStableId(value, "capabilityId"))
                .sorted()
                .forEach(pending::addLast);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            RuntimeCapability capability = requireCapability(current);
            if (visited.add(current)) {
                capability.requires().forEach(pending::addLast);
            }
        }
        return visited.stream().sorted().map(capabilities::get).toList();
    }

    /**
     * 返回能力闭包对应的 Maven 坐标，去重并稳定排序。
     *
     * @param rootCapabilityIds 根能力 ID。
     * @return 不可变 Maven 坐标列表。
     */
    public List<MavenCoordinate> artifactsFor(final Collection<String> rootCapabilityIds) {
        return closure(rootCapabilityIds).stream()
                .flatMap(capability -> capability.artifacts().stream())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 返回显式 provider 及其能力/实现依赖需要的 Maven 坐标。
     *
     * @param providerIds provider ID；不可为空。
     * @return 去重并稳定排序的 Maven 坐标。
     */
    public List<MavenCoordinate> artifactsForProviders(final Collection<ComponentId> providerIds) {
        return Objects.requireNonNull(providerIds, "providerIds").stream()
                .map(id -> requireProvider(Objects.requireNonNull(id, "providerId")))
                .flatMap(provider -> java.util.stream.Stream.concat(
                        artifactsFor(java.util.stream.Stream.concat(
                                provider.provides().stream(),
                                provider.requires().stream()).toList()).stream(),
                        provider.artifacts().stream()))
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 校验一个基础 catalog 的 descriptor 与共享模型完全一致。
     *
     * <p>该方法用于 Starter 冻结内置 catalog 前发现模型漂移。业务扩展 provider 应在基础
     * catalog 通过校验后追加，不应伪装成模型中的内置 provider。</p>
     *
     * @param descriptors 基础 catalog descriptor。
     * @param profileId 当前 profile ID。
     */
    public void validateDescriptors(
            final Collection<? extends ComponentDescriptor> descriptors,
            final String profileId) {
        String profile = RuntimeIdentifiers.requireStableId(profileId, "profileId");
        Map<ComponentId, ComponentDescriptor> actual = new TreeMap<>();
        for (ComponentDescriptor descriptor : Objects.requireNonNull(descriptors, "descriptors")) {
            ComponentDescriptor checked = Objects.requireNonNull(descriptor, "descriptor");
            if (actual.putIfAbsent(checked.id(), checked) != null) {
                throw new IllegalArgumentException("duplicate component descriptor: " + checked.id());
            }
        }
        List<RuntimeProviderCapability> expected = providers.values().stream()
                .filter(provider -> provider.profiles().contains(profile))
                .toList();
        if (!actual.keySet().equals(expected.stream()
                .map(RuntimeProviderCapability::providerId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)))) {
            throw new IllegalArgumentException("catalog providers do not match capability model profile " + profile);
        }
        expected.forEach(provider -> validateDescriptorShape(provider, actual.get(provider.providerId())));
    }

    /**
     * 校验单个内置 provider descriptor 与共享模型一致且允许用于指定 profile。
     *
     * @param descriptor provider descriptor；不可为空。
     * @param profileId profile ID；不可为空。
     */
    public void validateDescriptor(
            final ComponentDescriptor descriptor,
            final String profileId) {
        ComponentDescriptor checked = Objects.requireNonNull(descriptor, "descriptor");
        String profile = RuntimeIdentifiers.requireStableId(profileId, "profileId");
        RuntimeProviderCapability provider = providers.get(checked.id());
        if (provider == null || !provider.profiles().contains(profile)) {
            throw new IllegalArgumentException("provider is not declared for capability model profile " + profile);
        }
        validateDescriptorShape(provider, checked);
    }

    private void validateDescriptorShape(
            final RuntimeProviderCapability provider,
            final ComponentDescriptor descriptor) {
        Map<String, BindingKey<?>> provided = keysById(descriptor.provides());
        if (!provided.keySet().equals(new LinkedHashSet<>(provider.provides()))) {
            throw new IllegalArgumentException("provider capabilities do not match model: " + provider.providerId());
        }
        provided.forEach((capabilityId, key) -> {
            RuntimeCapability capability = requireCapability(capabilityId);
            if (capability.cardinality() != key.cardinality()) {
                throw new IllegalArgumentException("provider capability cardinality does not match model: "
                        + capabilityId);
            }
        });
        Set<String> expectedDependencies = new LinkedHashSet<>(provider.requires());
        provider.provides().stream()
                .map(this::requireCapability)
                .forEach(capability -> expectedDependencies.addAll(capability.requires()));
        if (!keysById(descriptor.requires()).keySet().equals(expectedDependencies)) {
            throw new IllegalArgumentException("provider dependencies do not match model: " + provider.providerId());
        }
    }

    private Map<String, BindingKey<?>> keysById(final Collection<BindingKey<?>> keys) {
        Map<String, BindingKey<?>> result = new TreeMap<>();
        for (BindingKey<?> key : keys) {
            BindingKey<?> existing = result.putIfAbsent(key.id(), key);
            if (existing != null && existing.cardinality() != key.cardinality()) {
                throw new IllegalArgumentException("binding signatures disagree for capability " + key.id());
            }
        }
        return result;
    }

    private void validateReferences() {
        capabilities.values().forEach(capability -> capability.requires().forEach(this::requireCapability));
        providers.values().forEach(provider -> {
            provider.provides().forEach(this::requireCapability);
            provider.requires().forEach(this::requireCapability);
        });
    }

    private void validateAcyclicDependencies() {
        Map<String, VisitState> states = new LinkedHashMap<>();
        for (String capabilityId : capabilities.keySet()) {
            visit(capabilityId, states);
        }
    }

    private void visit(final String capabilityId, final Map<String, VisitState> states) {
        VisitState state = states.get(capabilityId);
        if (state == VisitState.VISITING) {
            throw new IllegalArgumentException("capability dependency cycle contains " + capabilityId);
        }
        if (state == VisitState.VISITED) {
            return;
        }
        states.put(capabilityId, VisitState.VISITING);
        requireCapability(capabilityId).requires().forEach(required -> visit(required, states));
        states.put(capabilityId, VisitState.VISITED);
    }

    private RuntimeCapability requireCapability(final String capabilityId) {
        RuntimeCapability capability = capabilities.get(capabilityId);
        if (capability == null) {
            throw new IllegalArgumentException("unknown capability in model: " + capabilityId);
        }
        return capability;
    }

    private RuntimeProviderCapability requireProvider(final ComponentId providerId) {
        RuntimeProviderCapability provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("unknown provider in model: " + providerId);
        }
        return provider;
    }

    private enum VisitState {
        VISITING,
        VISITED
    }

    /** RuntimeCapabilityModel builder。 */
    public static final class Builder {

        private final String id;
        private final Map<String, RuntimeCapability> capabilities = new TreeMap<>();
        private final Map<ComponentId, RuntimeProviderCapability> providers = new TreeMap<>();

        private Builder(final String id) {
            this.id = RuntimeIdentifiers.requireStableId(id, "modelId");
        }

        public Builder capability(final RuntimeCapability capability) {
            RuntimeCapability checked = Objects.requireNonNull(capability, "capability");
            if (capabilities.putIfAbsent(checked.id(), checked) != null) {
                throw new IllegalArgumentException("duplicate capability in model: " + checked.id());
            }
            return this;
        }

        public Builder provider(final RuntimeProviderCapability provider) {
            RuntimeProviderCapability checked = Objects.requireNonNull(provider, "provider");
            if (providers.putIfAbsent(checked.providerId(), checked) != null) {
                throw new IllegalArgumentException("duplicate provider in model: " + checked.providerId());
            }
            return this;
        }

        public RuntimeCapabilityModel build() {
            return new RuntimeCapabilityModel(this);
        }
    }
}
