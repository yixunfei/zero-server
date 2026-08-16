package group.zn.zero.codegen.scaffold;

import group.zn.zero.runtime.capability.MavenCoordinate;
import java.util.Objects;

/**
 * 生成项目的直接依赖声明。
 *
 * @param coordinate 无版本坐标。
 * @param scope Maven scope；空字符串表示 compile。
 */
public record ScaffoldDependency(MavenCoordinate coordinate, String scope)
        implements Comparable<ScaffoldDependency> {

    public ScaffoldDependency {
        coordinate = Objects.requireNonNull(coordinate, "coordinate");
        scope = Objects.requireNonNull(scope, "scope").trim();
        if (!scope.isEmpty() && !scope.matches("[a-z][a-z-]*")) {
            throw new IllegalArgumentException("dependency scope is invalid");
        }
    }

    @Override
    public int compareTo(final ScaffoldDependency other) {
        int coordinateComparison = coordinate.compareTo(Objects.requireNonNull(other, "other").coordinate);
        return coordinateComparison != 0 ? coordinateComparison : scope.compareTo(other.scope);
    }
}
