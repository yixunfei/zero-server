package group.zn.zero.runtime.capability;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 不含版本的 Maven 能力坐标；版本由业务项目的 BOM 或生成请求统一决定。
 *
 * @param groupId Maven groupId。
 * @param artifactId Maven artifactId。
 * @author zn
 */
public record MavenCoordinate(String groupId, String artifactId)
        implements Comparable<MavenCoordinate> {

    private static final Pattern GROUP_ID = Pattern.compile(
            "[a-zA-Z_][a-zA-Z0-9_-]*(?:\\.[a-zA-Z_][a-zA-Z0-9_-]*)*");

    private static final Pattern ARTIFACT_ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]*");

    /** 创建并校验 Maven 坐标。 */
    public MavenCoordinate {
        groupId = requireMatch(groupId, GROUP_ID, "groupId");
        artifactId = requireMatch(artifactId, ARTIFACT_ID, "artifactId");
    }

    /**
     * 创建 zeroServer 模块坐标。
     *
     * @param artifactId 模块 artifactId。
     * @return 坐标；不可为空。
     */
    public static MavenCoordinate zero(final String artifactId) {
        return new MavenCoordinate("group.zn.zero", artifactId);
    }

    @Override
    public int compareTo(final MavenCoordinate other) {
        int groupComparison = groupId.compareTo(Objects.requireNonNull(other, "other").groupId);
        return groupComparison != 0 ? groupComparison : artifactId.compareTo(other.artifactId);
    }

    @Override
    public String toString() {
        return groupId + ':' + artifactId;
    }

    private static String requireMatch(final String value, final Pattern pattern, final String label) {
        String checked = Objects.requireNonNull(value, label).trim();
        if (!pattern.matcher(checked).matches()) {
            throw new IllegalArgumentException(label + " has an invalid Maven coordinate format");
        }
        return checked;
    }
}
