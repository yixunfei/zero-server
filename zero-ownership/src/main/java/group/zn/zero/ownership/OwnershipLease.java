package group.zn.zero.ownership;

import java.time.Instant;
import java.util.Objects;

/** 权威 ownership 租约；epoch 是所有写入必须携带的 fencing token。 */
public record OwnershipLease(
        OwnershipResource resource,
        ProcessIncarnation owner,
        long epoch,
        Instant expiresAt) {
    /** 校验租约字段。 */
    public OwnershipLease {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (epoch <= 0) {
            throw new IllegalArgumentException("epoch must be positive");
        }
    }

    /** 判断租约在给定时刻是否仍有效。 */
    public boolean validAt(final Instant now) {
        return !Objects.requireNonNull(now, "now").isAfter(expiresAt);
    }
}
