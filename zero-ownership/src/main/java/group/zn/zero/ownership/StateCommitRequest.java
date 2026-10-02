package group.zn.zero.ownership;

import java.util.Objects;

/** 携带 fencing token、CAS 版本和幂等键的状态提交请求。 */
public record StateCommitRequest<T>(
        OwnershipResource resource,
        OwnershipLease lease,
        StateVersion expectedVersion,
        String requestId,
        T payload) {
    /** 校验提交请求。 */
    public StateCommitRequest {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        requireText(requestId, "requestId");
        if (!resource.equals(lease.resource())) {
            throw new IllegalArgumentException("lease resource does not match request resource");
        }
    }

    private static void requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
