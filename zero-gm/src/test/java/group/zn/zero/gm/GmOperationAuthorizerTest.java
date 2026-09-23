package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GmOperationAuthorizerTest {
    @Test
    void authorizesRbacIpApprovalAndDualReview() {
        AtomicReference<GmAuthorizationAuditEvent> audit = new AtomicReference<>();
        GmOperationAuthorizer authorizer = new GmOperationAuthorizer(
                new GmOperationAuthorizationPolicy(Set.of("gm-admin"), Set.of("gm:mail"),
                        Set.of("192.0.2.0/24"), true, true), audit::set);
        GmCommandContext context = new GmCommandContext("alice", "192.0.2.10", "trace",
                Set.of("gm-admin"), Set.of("gm:mail"), "approval", "APPROVED", Map.of());
        authorizer.authorize(context, "player-1", "token", "bob");
        assertEquals(null, audit.get());
    }

    @Test
    void rejectsWithStableCodeAndSafeAudit() {
        AtomicReference<GmAuthorizationAuditEvent> audit = new AtomicReference<>();
        GmOperationAuthorizer authorizer = new GmOperationAuthorizer(
                new GmOperationAuthorizationPolicy(Set.of("gm-admin"), Set.of("gm:mail"), Set.of(), false, false),
                audit::set);
        GmCommandContext context = new GmCommandContext("secret-operator", "198.51.100.1", "trace",
                Set.of("viewer"), Set.of(), "", "", Map.of("token", "secret"));
        ZeroException error = assertThrows(ZeroException.class,
                () -> authorizer.authorize(context, "secret-target", null, null));
        assertEquals(GmErrorCode.OPERATION_AUTHORIZATION_DENIED, error.errorCode());
        assertEquals(GmAuthorizationFailureReason.ROLE_PERMISSION_DENIED, audit.get().reason());
        assertTrue(audit.get().toString().contains("redacted:"));
        assertTrue(!audit.get().toString().contains("secret-operator"));
        assertTrue(!audit.get().toString().contains("secret-target"));
    }

    @Test
    void rejectsSourceBeforeApprovalAndExecution() {
        AtomicReference<GmAuthorizationFailureReason> reason = new AtomicReference<>();
        GmOperationAuthorizer authorizer = new GmOperationAuthorizer(
                new GmOperationAuthorizationPolicy(Set.of(), Set.of(), Set.of("192.0.2.0/24"), true, false),
                event -> reason.set(event.reason()));
        GmCommandContext context = new GmCommandContext("alice", "203.0.113.4", "trace",
                Set.of(), Set.of(), "", "PENDING", Map.of());
        assertThrows(ZeroException.class, () -> authorizer.authorize(context, "target", "secret", null));
        assertEquals(GmAuthorizationFailureReason.SOURCE_IP_DENIED, reason.get());
    }

    @Test
    void supportsApplicationApprovalVerifierAndRejectsSameReviewer() {
        AtomicReference<GmAuthorizationFailureReason> reason = new AtomicReference<>();
        GmOperationAuthorizer authorizer = new GmOperationAuthorizer(
                new GmOperationAuthorizationPolicy(Set.of(), Set.of(), Set.of(), true, true),
                GmSourceIpPolicy.allowAll(), (context, target, token) ->
                        "opaque-approved".equals(token) ? GmApprovalDecision.APPROVED : GmApprovalDecision.INVALID,
                event -> reason.set(event.reason()));
        GmCommandContext context = GmCommandContext.simple("alice", "192.0.2.10", "trace");
        assertThrows(ZeroException.class, () -> authorizer.authorize(context, "target", "opaque-approved", "alice"));
        assertEquals(GmAuthorizationFailureReason.DUAL_REVIEW_REQUIRED, reason.get());
    }

    @Test
    void rejectsMalformedCidrAtPolicyConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> GmSourceIpPolicy.of(Set.of("192.0.2.0/33")));
    }
}
