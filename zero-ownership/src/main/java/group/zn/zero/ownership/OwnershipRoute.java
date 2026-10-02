package group.zn.zero.ownership;

import java.time.Instant;
import java.util.Objects;

/** 可选的 owner 路由记录；endpoint 由应用定义并可指向 RPC、网关或本地地址。 */
public record OwnershipRoute(
        OwnershipResource resource,
        ProcessIncarnation owner,
        long epoch,
        String endpoint,
        Instant expiresAt) {
    /** 校验路由字段。 */
    public OwnershipRoute {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(owner, "owner");
        requireText(endpoint, "endpoint");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (epoch <= 0) {
            throw new IllegalArgumentException("epoch must be positive");
        }
    }

    /** 判断路由是否仍可用于转发。 */
    public boolean validAt(final Instant now) {
        return !Objects.requireNonNull(now, "now").isAfter(expiresAt);
    }

    private static void requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
