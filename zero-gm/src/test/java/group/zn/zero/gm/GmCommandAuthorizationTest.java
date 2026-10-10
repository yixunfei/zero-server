package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 命令实参、审批要求与端点授权必须一致；拒绝路径不进入业务。 @author zn */
class GmCommandAuthorizationTest {
    /** 请求目标不能为不同的 DSL 实参提供授权，预演也不能绕过。 */
    @Test void rejectsTargetMismatchBeforeApprovalAndExecution() {
        var calls = new AtomicInteger();
        var approvals = new AtomicInteger();
        var audits = new ArrayList<GmAuthorizationAuditEvent>();
        var endpoint = endpoint(true, false, calls, (context, target, token) -> {
            approvals.incrementAndGet();
            return GmApprovalDecision.APPROVED;
        }, audits);
        for (boolean dryRun : List.of(false, true)) {
            assertEquals(GmErrorCode.OPERATION_AUTHORIZATION_DENIED.code(),
                    endpoint.handle(request("B", "A", dryRun)).code());
        }
        assertEquals(0, calls.get());
        assertEquals(0, approvals.get());
        assertEquals(2, audits.size());
        assertFalse(audits.toString().contains("sensitive-token"));
    }

    /** 策略或定义任一要求审批时都必须验证；幂等重放仍重新校验审批。 */
    @Test void enforcesEitherApprovalRequirementAndRechecksCompletedRequests() {
        for (boolean commandApproval : List.of(false, true)) {
            var calls = new AtomicInteger();
            var approved = new AtomicBoolean();
            var endpoint = endpoint(commandApproval, !commandApproval, calls, (context, target, token) -> {
                assertEquals("A", target);
                assertEquals("sensitive-token", token);
                return approved.get() ? GmApprovalDecision.APPROVED : GmApprovalDecision.INVALID;
            }, new ArrayList<>());
            for (boolean dryRun : List.of(false, true)) {
                var request = request("A", "A", dryRun);
                assertEquals(GmErrorCode.OPERATION_AUTHORIZATION_DENIED.code(), endpoint.handle(request).code());
                approved.set(true);
                assertEquals("ZERO-OK", endpoint.handle(request).code());
                approved.set(false);
                assertEquals(GmErrorCode.OPERATION_AUTHORIZATION_DENIED.code(), endpoint.handle(request).code());
            }
            assertEquals(2, calls.get());
        }
    }

    private static GmOperationRequest request(String argument, String target, boolean dryRun) {
        return new GmOperationRequest(GmCommandContext.simple("alice", "192.0.2.1", "trace"),
                "/grant " + argument, target, "sensitive-token", "", "request-key", dryRun);
    }

    private static GmOperationEndpoint endpoint(boolean commandApproval, boolean policyApproval,
            AtomicInteger calls, GmApprovalVerifier verifier, List<GmAuthorizationAuditEvent> audits) {
        var registry = new GmCommandRegistry();
        registry.register(new GmCommandDefinition(List.of("grant"), List.of("uid"), "grant",
                GmCommandRisk.LOW, "uid", commandApproval), new GmCommandHandler() {
                    @Override public GmDryRunResult dryRun(GmCommandExecutionRequest request) {
                        calls.incrementAndGet();
                        return GmDryRunResult.accepted("grant", "preview", Map.of());
                    }
                    @Override public GmCommandExecutionResult execute(GmCommandExecutionRequest request) {
                        calls.incrementAndGet();
                        return GmCommandExecutionResult.success("grant", "ok", Map.of());
                    }
                });
        var policy = new GmOperationAuthorizationPolicy(Set.of(), Set.of(), Set.of("0.0.0.0/0", "::/0"), policyApproval, false);
        return new GmOperationEndpoint(GmCommandExecutor.systemClock(registry, event -> { }),
                new GmOperationAuthorizer(policy, policy.sourceIpPolicy(), verifier, audits::add),
                new InMemoryGmIdempotencyStore());
    }
}
