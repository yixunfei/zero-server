package group.zn.zero.gm;

import java.util.Objects;
import java.util.Set;

/** Immutable authorization requirements for one GM operation boundary. */
public record GmOperationAuthorizationPolicy(
        Set<String> allowedRoles,
        Set<String> requiredPermissions,
        Set<String> allowedSourceIps,
        boolean approvalRequired,
        boolean dualReviewRequired) {
    public GmOperationAuthorizationPolicy {
        allowedRoles = Set.copyOf(Objects.requireNonNull(allowedRoles, "allowedRoles"));
        requiredPermissions = Set.copyOf(Objects.requireNonNull(requiredPermissions, "requiredPermissions"));
        allowedSourceIps = Set.copyOf(Objects.requireNonNull(allowedSourceIps, "allowedSourceIps"));
    }

    /** Returns the policy as a validated, pluggable source-IP matcher. */
    public GmSourceIpPolicy sourceIpPolicy() {
        return allowedSourceIps.isEmpty() ? GmSourceIpPolicy.allowAll() : GmSourceIpPolicy.of(allowedSourceIps);
    }
}
