package group.zn.zero.runtime.capability;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.internal.RuntimeIdentifiers;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 不含实现类或工厂的 provider 逻辑声明。
 *
 * @param providerId 与 ComponentDescriptor 一致的稳定 provider ID。
 * @param provides provider 提供的逻辑能力 ID。
 * @param requires provider 实现自身额外依赖的逻辑能力 ID。
 * @param profiles provider 可注册到的运行档位。
 * @param artifacts provider 实现自身额外需要的 Maven 坐标。
 * @author zn
 */
public record RuntimeProviderCapability(
        ComponentId providerId,
        List<String> provides,
        List<String> requires,
        List<String> profiles,
        List<MavenCoordinate> artifacts) {

    /** 创建、排序并冻结 provider 逻辑声明。 */
    public RuntimeProviderCapability {
        providerId = Objects.requireNonNull(providerId, "providerId");
        provides = stableIds(provides, "providedCapabilityId");
        requires = stableIds(requires, "requiredCapabilityId");
        profiles = stableIds(profiles, "profileId");
        artifacts = Objects.requireNonNull(artifacts, "artifacts").stream()
                .map(value -> Objects.requireNonNull(value, "artifact"))
                .distinct()
                .sorted()
                .toList();
        if (provides.isEmpty()) {
            throw new IllegalArgumentException("provider capabilities must not be empty");
        }
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("provider profiles must not be empty");
        }
        if (provides.stream().anyMatch(requires::contains)) {
            throw new IllegalArgumentException("provider cannot require a capability it provides");
        }
    }

    private static List<String> stableIds(final Collection<String> values, final String label) {
        return Objects.requireNonNull(values, label).stream()
                .map(value -> RuntimeIdentifiers.requireStableId(value, label))
                .distinct()
                .sorted()
                .toList();
    }
}
