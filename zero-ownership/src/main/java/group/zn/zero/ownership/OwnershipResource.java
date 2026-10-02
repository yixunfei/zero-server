package group.zn.zero.ownership;

import java.util.Objects;

/** 可被跨进程管理的资源身份；generation 用于隔离销毁后重建的同名资源。 */
public record OwnershipResource(String resourceType, String resourceId, long generation) {
    /** 校验资源身份。 */
    public OwnershipResource {
        requireText(resourceType, "resourceType");
        requireText(resourceId, "resourceId");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
    }

    private static void requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
