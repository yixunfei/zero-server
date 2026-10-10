package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.core.error.ZeroException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 在途幂等、拒绝审计及敏感字段回归。 @author zn */
class GmOperationReliabilityTest {
    /** 其他请求清理终态不得回收仍在执行业务的 claim。 */
    @Test void inFlightClaimSurvivesRetentionAndCanFinish() {
        var store = new InMemoryGmIdempotencyStore();
        var now = Instant.EPOCH;
        store.claim("running", "fingerprint", now, Duration.ofSeconds(1));
        store.claim("other", "fingerprint", now.plusSeconds(100), Duration.ofSeconds(1));
        assertEquals(GmIdempotencyStore.State.IN_PROGRESS,
                store.claim("running", "fingerprint", now.plusSeconds(100), Duration.ofSeconds(1)).state());
        store.fail("running", "fingerprint", now.plusSeconds(200));
    }

    /** 诊断字符串不得持有令牌、身份或参数。 */
    @Test void requestDiagnosticsDoNotRevealSecrets() {
        var context = new GmCommandContext("secret-user", "192.0.2.1", "trace", Set.of(), Set.of(),
                "secret-approval", "APPROVED", Map.of("token", "secret-token"));
        var request = new GmOperationRequest(context, "/ping secret-argument", "secret-target", "secret-token", "bob", false);
        assertFalse(context.toString().contains("secret"));
        assertFalse(request.toString().contains("secret"));
    }

    /** 拒绝审计失败仍保持原鉴权错误，并附带审计根因。 */
    @Test void authorizationFailureSurvivesAuditFailure() {
        var policy = new GmOperationAuthorizationPolicy(Set.of("admin"), Set.of(), Set.of("192.0.2.1"), false, false);
        var authorizer = new GmOperationAuthorizer(policy, event -> { throw new IllegalStateException("sink down"); });
        var failure = assertThrows(ZeroException.class, () -> authorizer.authorize(
                GmCommandContext.simple("user", "192.0.2.1", "trace"), "server", "", ""));
        assertEquals(GmErrorCode.OPERATION_AUTHORIZATION_DENIED, failure.errorCode());
        assertEquals(1, failure.getSuppressed().length);
    }

    /** 非 RuntimeException 业务故障保持 Error 实例且审计失败阶段。 */
    @Test void handlerErrorIsAuditedAndRethrown() {
        var events = new ArrayList<GmAuditEvent>();
        var error = new AssertionError("private business failure");
        var executor = GmCommandExecutor.systemClock(registry(GmCommandRisk.LOW, error), events::add);
        assertSame(error, assertThrows(AssertionError.class, () -> executor.execute(context(), "/ping")));
        assertEquals(GmAuditPhase.EXECUTE_FAILED, events.getLast().phase());
        assertEquals(GmBusinessCommitState.UNKNOWN, events.getLast().businessCommitState());
    }

    /** 高风险定义即使漏写 approvalRequired 也不能裸执行。 */
    @Test void highRiskCommandRequiresApproval() {
        var endpoint = new GmOperationEndpoint(GmCommandExecutor.systemClock(registry(GmCommandRisk.HIGH, null), e -> { }), authorizer());
        assertEquals(GmErrorCode.OPERATION_AUTHORIZATION_DENIED.code(),
                endpoint.handle(new GmOperationRequest(context(), "/ping", "server", "", "", false)).code());
    }

    /** 未知指令必须留安全拒绝记录。 */
    @Test void unresolvedCommandIsAudited() {
        var events = new ArrayList<GmAuditEvent>();
        var endpoint = new GmOperationEndpoint(GmCommandExecutor.systemClock(registry(GmCommandRisk.LOW, null), events::add), authorizer());
        assertEquals(GmErrorCode.COMMAND_NOT_FOUND.code(),
                endpoint.handle(new GmOperationRequest(context(), "/unknown secret", "server", "", "", false)).code());
        assertEquals(1, events.size());
        assertFalse(events.toString().contains("secret"));
    }

    /** 非 REST 调用也有解析容量限制。 */
    @Test void dslHasBoundedWork() {
        assertThrows(ZeroException.class, () -> GmCommandDsl.parse("/ping " + "a ".repeat(10000)));
    }

    private static GmCommandContext context() { return GmCommandContext.simple("user", "192.0.2.1", "trace"); }
    private static GmOperationAuthorizer authorizer() {
        return new GmOperationAuthorizer(new GmOperationAuthorizationPolicy(Set.of(), Set.of(), Set.of("192.0.2.1"), false, false), e -> { });
    }
    private static GmCommandRegistry registry(GmCommandRisk risk, Error failure) {
        var registry = new GmCommandRegistry();
        registry.register(new GmCommandDefinition(List.of("ping"), List.of(), "ping", risk, "", false), new GmCommandHandler() {
            @Override public GmDryRunResult dryRun(GmCommandExecutionRequest request) {
                if (failure != null) throw failure;
                return GmDryRunResult.accepted("ping", "ok", Map.of());
            }
            @Override public GmCommandExecutionResult execute(GmCommandExecutionRequest request) {
                if (failure != null) throw failure;
                return GmCommandExecutionResult.success("ping", "ok", Map.of());
            }
        });
        return registry;
    }
}
