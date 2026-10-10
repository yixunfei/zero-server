package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证空策略、可信复核和审计落地边界。 @author zn */
class GmSafetyBoundaryTest {
    /** 缺省配置不能授予 GM 操作权限。 */
    @Test void emptyPolicyDeniesOperation() {
        var authorizer = new GmOperationAuthorizer(
                new GmOperationAuthorizationPolicy(Set.of(), Set.of(), Set.of(), false, false), event -> { });
        assertThrows(ZeroException.class, () -> authorizer.authorize(
                GmCommandContext.simple("alice", "192.0.2.1", "trace"), "target", "", ""));
    }

    /** 操作者自填另一姓名不能构成第二人复核。 */
    @Test void selfAssertedReviewerCannotAuthorize() {
        var policy = new GmOperationAuthorizationPolicy(Set.of(), Set.of(), Set.of("192.0.2.1"), true, true);
        var authorizer = new GmOperationAuthorizer(policy, policy.sourceIpPolicy(),
                (context, target, token) -> GmApprovalDecision.APPROVED, event -> { });
        assertThrows(ZeroException.class, () -> authorizer.authorize(
                GmCommandContext.simple("alice", "192.0.2.1", "trace"), "target", "token", "bob"));
    }

    /** 审批服务故障也必须保持授权拒绝，并保留故障作为 suppressed 根因。 */
    @Test void approvalVerifierFailureFailsClosed() {
        var policy = new GmOperationAuthorizationPolicy(Set.of(), Set.of(), Set.of("192.0.2.1"), true, false);
        var authorizer = new GmOperationAuthorizer(policy, policy.sourceIpPolicy(),
                (context, target, token) -> { throw new IllegalStateException("approval unavailable"); }, event -> { });
        var failure = assertThrows(ZeroException.class, () -> authorizer.authorize(
                GmCommandContext.simple("alice", "192.0.2.1", "trace"), "target", "token", ""));
        assertEquals(GmErrorCode.OPERATION_AUTHORIZATION_DENIED, failure.errorCode());
        assertEquals(1, failure.getSuppressed().length);
    }

    /** 未获存储确认的审计必须显式失败。 */
    @Test void rejectedOrUnprovenDuplicateAuditFails() {
        var event = events().get(0);
        for (var result : List.of(GmAuditAppendResult.REJECTED, GmAuditAppendResult.DUPLICATE)) {
            var hook = new RecordGmAuditHook(record -> result);
            assertThrows(GmAuditFailureException.class, () -> hook.record(event));
        }
    }

    /** 同一个事件重复转换稳定，不同操作即使同毫秒同 trace 也不得合并。 */
    @Test void distinctInvocationsHaveDistinctStableAuditIds() {
        var events = events();
        assertEquals(GmAuditRecord.from(events.get(0)), GmAuditRecord.from(events.get(0)));
        assertEquals(GmAuditRecord.from(events.get(0)).operationId(), GmAuditRecord.from(events.get(1)).operationId());
        assertNotEquals(GmAuditRecord.from(events.get(0)).operationId(), GmAuditRecord.from(events.get(2)).operationId());
        assertNotEquals(GmAuditRecord.from(events.get(0)).eventId(), GmAuditRecord.from(events.get(2)).eventId());
    }

    /** 复核收到实际命令和全部参数；故障或身份不匹配不能到达 handler。 */
    @Test void criticalOperationBindsReviewerAndFailsClosedThroughEndpoint() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var events = new ArrayList<GmAuditEvent>();
        var reasons = new ArrayList<GmAuthorizationAuditEvent>();
        var registry = new GmCommandRegistry();
        registry.register(new GmCommandDefinition(List.of("grant"), List.of("uid", "amount"), "grant",
                GmCommandRisk.CRITICAL, "uid", false), new GmCommandHandler() {
                    @Override public GmDryRunResult dryRun(GmCommandExecutionRequest request) {
                        calls.incrementAndGet();
                        return GmDryRunResult.accepted("grant", "preview", Map.of());
                    }
                    @Override public GmCommandExecutionResult execute(GmCommandExecutionRequest request) {
                        calls.incrementAndGet();
                        return GmCommandExecutionResult.success("grant", "ok", Map.of());
                    }
                });
        var policy = new GmOperationAuthorizationPolicy(Set.of(), Set.of(), Set.of("192.0.2.1"), false, false);
        var authorizer = new GmOperationAuthorizer(policy, policy.sourceIpPolicy(), (context, target, token) -> {
            if ("unavailable".equals(token)) throw new IllegalStateException("approval failure");
            return GmApprovalDecision.APPROVED;
        }, (request, target, token, reviewer) -> {
            assertEquals("alice", request.context().operator());
            assertEquals("grant", request.commandKey());
            assertEquals("A", target);
            assertEquals("A", request.namedArguments().get("uid"));
            if ("reviewer-down".equals(token)) throw new IllegalStateException("reviewer failure");
            return "token".equals(token) && "10".equals(request.namedArguments().get("amount")) ? "bob" : "";
        }, reasons::add);
        var endpoint = new GmOperationEndpoint(GmCommandExecutor.systemClock(registry, events::add), authorizer);
        var context = GmCommandContext.simple("alice", "192.0.2.1", "trace");
        for (boolean dryRun : List.of(false, true)) {
            for (String token : List.of("unavailable", "reviewer-down")) {
                var response = endpoint.handle(new GmOperationRequest(context, "/grant A 10", "A", token, "bob", dryRun));
                assertEquals(GmErrorCode.OPERATION_AUTHORIZATION_DENIED.code(), response.code());
                assertEquals(GmAuthorizationFailureReason.VERIFICATION_UNAVAILABLE, reasons.getLast().reason());
                assertEquals(GmAuditPhase.REJECTED, events.getLast().phase());
            }
            assertEquals(GmErrorCode.OPERATION_AUTHORIZATION_DENIED.code(), endpoint.handle(
                    new GmOperationRequest(context, "/grant A 11", "A", "token", "bob", dryRun)).code());
            assertEquals(dryRun ? 1 : 0, calls.get());
            assertEquals("ZERO-OK", endpoint.handle(
                    new GmOperationRequest(context, "/grant A 10", "A", "token", "bob", dryRun)).code());
        }
        assertEquals(2, calls.get());
    }

    /** 分页使用构造时快照，失效游标稳定失败而非重复返回第一页。 */
    @Test void auditQuerySnapshotSurvivesSourceChangesAndRejectsInvalidCursor() {
        var records = new ArrayList<>(events().stream().map(GmAuditRecord::from).toList());
        var store = new InMemoryGmAuditQueryStore(records);
        var first = store.query(new GmAuditQuery(Instant.EPOCH, Instant.EPOCH.plusSeconds(1), null, null, 1));
        records.clear();
        var rest = store.query(new GmAuditQuery(Instant.EPOCH, Instant.EPOCH.plusSeconds(1), null, first.nextCursor(), 10));
        assertEquals(3, rest.records().size());
        var failure = assertThrows(ZeroException.class, () -> store.query(
                new GmAuditQuery(Instant.EPOCH, Instant.EPOCH.plusSeconds(1), null, "absent", 10)));
        assertEquals(GmErrorCode.AUDIT_CURSOR_INVALID, failure.errorCode());
    }

    /** HMAC 对相同部署密钥稳定，字段分界、密钥和完整审批事实参与指纹。 */
    @Test void fingerprintsBindFieldsAndDeploymentKeyWithoutTrace() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        var first = GmOperationFingerprint.hmac("v1", key);
        var second = GmOperationFingerprint.hmac("v1", key);
        var context = GmCommandContext.simple("alice", "192.0.2.1", "trace");
        var request = new GmOperationRequest(context, "/ping", "ab", "c", "bob", false);
        assertEquals(first.fingerprint(request), second.fingerprint(request));
        assertEquals(first.fingerprint(request), first.fingerprint(new GmOperationRequest(
                GmCommandContext.simple("alice", "192.0.2.1", "other"), "/ping", "ab", "c", "bob", false)));
        assertNotEquals(first.fingerprint(request), first.fingerprint(
                new GmOperationRequest(context, "/ping", "a", "bc", "bob", false)));
        assertNotEquals(first.fingerprint(request), first.fingerprint(
                new GmOperationRequest(context, "/ping", "ab", "c", "charlie", false)));
        key[0]++;
        assertEquals(first.fingerprint(request), second.fingerprint(request));
        assertNotEquals(first.fingerprint(request), GmOperationFingerprint.hmac("v1", key).fingerprint(request));
    }

    private static List<GmAuditEvent> events() {
        var registry = new GmCommandRegistry();
        registry.register(new GmCommandDefinition(List.of("ping"), List.of(), "ping", GmCommandRisk.LOW, "", false),
                new GmCommandHandler() {
                    @Override public GmDryRunResult dryRun(GmCommandExecutionRequest request) {
                        return GmDryRunResult.accepted("ping", "ok", Map.of());
                    }
                    @Override public GmCommandExecutionResult execute(GmCommandExecutionRequest request) {
                        return GmCommandExecutionResult.success("ping", "ok", Map.of());
                    }
                });
        var events = new ArrayList<GmAuditEvent>();
        var executor = new GmCommandExecutor(registry, events::add, GmAuditAttributionFactory.redacted(),
                GmAuditFingerprintPolicy.structureOnly(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var context = GmCommandContext.simple("alice", "192.0.2.1", "same-trace");
        executor.execute(context, "/ping");
        executor.execute(context, "/ping");
        return events;
    }
}
