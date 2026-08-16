package group.zn.zero.runtime.capability;

import group.zn.zero.runtime.api.BindingCardinality;
import group.zn.zero.runtime.internal.RuntimeIdentifiers;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 与实现类型无关的逻辑运行时能力定义。
 *
 * @param id 与 typed binding key 一致的稳定 ID。
 * @param cardinality 单值或多值基数。
 * @param requires 逻辑依赖能力 ID。
 * @param profiles 能力可用于的运行档位。
 * @param artifacts 提供该 API 或本地实现的 Maven 模块坐标。
 * @author zn
 */
public record RuntimeCapability(
        String id,
        BindingCardinality cardinality,
        List<String> requires,
        List<String> profiles,
        List<MavenCoordinate> artifacts) {

    /** 创建、排序并冻结逻辑能力。 */
    public RuntimeCapability {
        id = RuntimeIdentifiers.requireStableId(id, "capabilityId");
        cardinality = Objects.requireNonNull(cardinality, "cardinality");
        requires = stableIds(requires, "requiredCapabilityId");
        profiles = stableIds(profiles, "profileId");
        artifacts = Objects.requireNonNull(artifacts, "artifacts").stream()
                .map(artifact -> Objects.requireNonNull(artifact, "artifact"))
                .distinct()
                .sorted()
                .toList();
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("capability profiles must not be empty");
        }
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("capability artifacts must not be empty");
        }
        if (requires.contains(id)) {
            throw new IllegalArgumentException("capability cannot require itself");
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
