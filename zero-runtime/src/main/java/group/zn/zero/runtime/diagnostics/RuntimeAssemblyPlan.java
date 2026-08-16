package group.zn.zero.runtime.diagnostics;

import group.zn.zero.runtime.api.BindingKey;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.assembly.SelectionSourceKind;
import group.zn.zero.runtime.config.ResolvedConfigMetadata;
import group.zn.zero.runtime.spi.ComponentKind;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 不创建 provider 或 build resource 的确定性 runtime 装配计划。
 *
 * @param profile profile 名称。
 * @param preset preset 名称；为空表示未使用。
 * @param rootRequirements 应用根 capability。
 * @param components 拓扑顺序组件。
 * @param edges 稳定排序依赖边。
 * @param config 配置来源元数据，不包含值。
 * @author zn
 */
public record RuntimeAssemblyPlan(
        String profile,
        Optional<String> preset,
        List<String> rootRequirements,
        List<ComponentPlan> components,
        List<DependencyEdge> edges,
        List<ResolvedConfigMetadata> config) {

    public RuntimeAssemblyPlan {
        profile = Objects.requireNonNull(profile, "profile");
        preset = Objects.requireNonNull(preset, "preset");
        rootRequirements = List.copyOf(Objects.requireNonNull(rootRequirements, "rootRequirements"));
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        config = List.copyOf(Objects.requireNonNull(config, "config"));
    }

    /** 计划中的单个组件。 */
    public record ComponentPlan(
            ComponentId componentId,
            ComponentKind kind,
            String providerType,
            String catalogSource,
            List<BindingKey<?>> provides,
            List<BindingKey<?>> requires,
            List<BindingKey<?>> optional,
            List<ComponentId> startAfter,
            List<SelectionReason> selectionReasons) {

        public ComponentPlan {
            componentId = Objects.requireNonNull(componentId, "componentId");
            kind = Objects.requireNonNull(kind, "kind");
            providerType = Objects.requireNonNull(providerType, "providerType");
            catalogSource = Objects.requireNonNull(catalogSource, "catalogSource");
            provides = List.copyOf(Objects.requireNonNull(provides, "provides"));
            requires = List.copyOf(Objects.requireNonNull(requires, "requires"));
            optional = List.copyOf(Objects.requireNonNull(optional, "optional"));
            startAfter = List.copyOf(Objects.requireNonNull(startAfter, "startAfter"));
            selectionReasons = List.copyOf(Objects.requireNonNull(selectionReasons, "selectionReasons"));
        }
    }

    /** provider 被激活的可审计原因。 */
    public record SelectionReason(
            String bindingId,
            ComponentId providerId,
            SelectionSourceKind sourceKind,
            String sourceId,
            Optional<ComponentId> previousProviderId) {

        public SelectionReason {
            bindingId = Objects.requireNonNull(bindingId, "bindingId");
            providerId = Objects.requireNonNull(providerId, "providerId");
            sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
            sourceId = Objects.requireNonNull(sourceId, "sourceId");
            previousProviderId = Objects.requireNonNull(previousProviderId, "previousProviderId");
        }
    }

    /** 组件间依赖或排序边。 */
    public record DependencyEdge(
            ComponentId from,
            ComponentId to,
            DependencyEdgeKind kind,
            String reason) {

        public DependencyEdge {
            from = Objects.requireNonNull(from, "from");
            to = Objects.requireNonNull(to, "to");
            kind = Objects.requireNonNull(kind, "kind");
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    /** 依赖边语义。 */
    public enum DependencyEdgeKind {
        REQUIRED,
        OPTIONAL,
        START_AFTER
    }
}
