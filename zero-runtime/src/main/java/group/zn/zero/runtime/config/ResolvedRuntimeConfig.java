package group.zn.zero.runtime.config;

import group.zn.zero.runtime.api.ComponentId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 所有已选组件的 typed 配置快照。
 *
 * @author zn
 */
public final class ResolvedRuntimeConfig {

    private final Map<ComponentId, ComponentConfig> components;
    private final List<ResolvedConfigMetadata> metadata;
    private final RuntimeConfigDiagnostics diagnostics;

    ResolvedRuntimeConfig(
            final Map<ComponentId, ComponentConfig> components,
            final List<ResolvedConfigMetadata> metadata,
            final RuntimeConfigDiagnostics diagnostics) {
        this.components = Map.copyOf(Objects.requireNonNull(components, "components"));
        this.metadata = List.copyOf(Objects.requireNonNull(metadata, "metadata"));
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public ComponentConfig component(final ComponentId componentId) {
        ComponentId checked = Objects.requireNonNull(componentId, "componentId");
        ComponentConfig config = components.get(checked);
        return config == null ? new ComponentConfig(checked, Map.of()) : config;
    }

    public List<ResolvedConfigMetadata> metadata() {
        return metadata;
    }

    /** 返回解析来源与脱敏有效值；只读、不可变、按 schema 顺序稳定排序。 */
    public RuntimeConfigDiagnostics diagnostics() {
        return diagnostics;
    }

    @Override
    public String toString() {
        return "ResolvedRuntimeConfig{components=" + components.keySet() + ", metadata=" + metadata
                + ", diagnostics=" + diagnostics + '}';
    }
}
