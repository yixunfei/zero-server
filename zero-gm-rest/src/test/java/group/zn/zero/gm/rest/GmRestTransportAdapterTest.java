package group.zn.zero.gm.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import group.zn.zero.gm.GmCommandContext;
import group.zn.zero.gm.GmCommandExecutionResult;
import group.zn.zero.gm.GmCommandDefinition;
import group.zn.zero.gm.GmCommandExecutionRequest;
import group.zn.zero.gm.GmCommandExecutor;
import group.zn.zero.gm.GmCommandHandler;
import group.zn.zero.gm.GmCommandRegistry;
import group.zn.zero.gm.GmCommandRisk;
import group.zn.zero.gm.GmDryRunResult;
import group.zn.zero.gm.GmApprovalDecision;
import group.zn.zero.gm.GmOperationAuthorizationPolicy;
import group.zn.zero.gm.GmOperationAuthorizer;
import group.zn.zero.gm.GmOperationEndpoint;
import group.zn.zero.gm.GmErrorCode;
import group.zn.zero.gm.GmIdentityProvider;
import group.zn.zero.gm.GmOperationResponse;
import group.zn.zero.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** REST 请求不能误执行，预演与错误结果可诊断且自由数据默认隐藏。 @author zn */
class GmRestTransportAdapterTest {
    /** 固定身份，与请求中的 traceId 匹配。 */
    private static final GmIdentityProvider IDENTITY = GmIdentityProvider.from(
            request -> GmCommandContext.simple("alice", "192.0.2.1", "t"));
    /** 必填字符串字段。 */
    private static final String BASE = "\"traceId\":\"t\",\"correlationId\":\"c\",\"idempotencyKey\":\"i\","
            + "\"command\":\"ping\",\"target\":\"server\"";

    private static HttpRequest request(final String body) {
        return new HttpRequest("POST", "/gm/operation", Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void routeMethodAndMetadataFailuresNeverExecuteBusiness() {
        var adapter = new GmRestTransportAdapter((operation, metadata) -> {
            throw new AssertionError("must not execute");
        }, IDENTITY, 4096);
        assertEquals(404, adapter.handle(new HttpRequest("POST", "/other", Map.of(), new byte[0])).status());
        assertEquals(405, adapter.handle(new HttpRequest("GET", "/gm/operation", Map.of(), new byte[0])).status());
        JsonObject body = JsonParser.parseString("{" + BASE + ",\"dryRun\":true}").getAsJsonObject();
        body.addProperty("traceId", "other");
        assertEquals(400, adapter.handle(request(body.toString())).status());
        body.addProperty("traceId", "t");
        body.addProperty("command", "x".repeat(257));
        assertEquals(400, adapter.handle(request(body.toString())).status());
        assertThrows(IllegalArgumentException.class, () -> new GmRestTransportAdapter((a,b) -> null,
                IDENTITY, 4096, Set.of("invalid key")));
    }

    @Test
    void unexpectedProviderFailuresReachHostErrorHandling() {
        var adapter = new GmRestTransportAdapter((a,b) -> { throw new IllegalStateException("unavailable"); },
                IDENTITY, 4096);
        assertThrows(IllegalStateException.class, () -> adapter.handle(request("{" + BASE + ",\"dryRun\":true}")));
    }

    @Test
    void invalidDryRunCannotReachIdentityOrDelegate() {
        AtomicInteger calls = new AtomicInteger();
        var adapter = new GmRestTransportAdapter((operation, metadata) -> {
            calls.incrementAndGet(); return GmOperationResponse.success(null, "t");
        }, request -> { calls.incrementAndGet(); return IDENTITY.resolve(request); }, 4096);
        for (String suffix : List.of("", ",\"dryRun\":\"true \"", ",\"dryRun\":\"true\"",
                ",\"dryRun\":null", ",\"dryRun\":1", ",\"dryRun\":{}", ",\"dryrun\":true",
                ",\"dryRun\":true,\"dryRun\":false", ",\"dryRun\":true,\"target\":\"other\"")) {
            assertEquals(400, adapter.handle(request("{" + BASE + suffix + "}")).status(), suffix);
        }
        assertEquals(0, calls.get());
    }

    @Test
    void acceptsBothBooleanValuesAndEscapedJson() {
        var adapter = new GmRestTransportAdapter((operation, metadata) -> {
            assertEquals("c", metadata.correlationId());
            assertEquals("say a,b \"你好\"", operation.rawCommand());
            return GmOperationResponse.success(new GmCommandExecutionResult("say", operation.dryRun(), true,
                    "private text", Map.of("summary", "one, two\nthree", "token", "secret"), null), "t");
        }, IDENTITY, 4096, Set.of("summary"));
        for (boolean dryRun : List.of(true, false)) {
            JsonObject input = JsonParser.parseString("{" + BASE + "}").getAsJsonObject();
            input.addProperty("command", "say a,b \"你好\"");
            input.addProperty("dryRun", dryRun);
            var response = adapter.handle(request(" \n" + input + "\n "));
            assertEquals(200, response.status());
            JsonObject output = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("t", output.get("traceId").getAsString());
            assertEquals("c", output.get("correlationId").getAsString());
            assertEquals(dryRun, output.getAsJsonObject("result").get("dryRun").getAsBoolean());
            assertEquals("one, two\nthree", output.getAsJsonObject("result").getAsJsonObject("data").get("summary").getAsString());
            assertFalse(output.toString().contains("secret"));
            assertFalse(output.toString().contains("private text"));
        }
    }

    @Test
    void rejectsTrailingMalformedAndOversizedInput() {
        var adapter = new GmRestTransportAdapter((a, b) -> { throw new AssertionError("must not execute"); }, IDENTITY, 4096);
        for (String body : List.of("not-json", "[]", "{" + BASE + ",\"dryRun\":true} {}",
                "{" + BASE + ",\"dryRun\":true,}", "{/*comment*/" + BASE + ",\"dryRun\":true}")) {
            assertEquals(400, adapter.handle(request(body)).status());
        }
        assertEquals(413, adapter.handle(request("x".repeat(4097))).status());
        byte[] invalidUtf8 = {(byte) 0xff};
        assertEquals(400, adapter.handle(new HttpRequest("POST", "/gm/operation", Map.of(), invalidUtf8)).status());
    }

    @Test
    void distinguishesAuthenticationPermissionValidationAndServerFailures() {
        var req = request("{" + BASE + ",\"dryRun\":false}");
        assertEquals(401, GmRestTransportAdapter.failClosed((a,b) -> { throw new AssertionError(); }).handle(req).status());
        Map<GmErrorCode, Integer> statuses = Map.of(GmErrorCode.OPERATION_AUTHORIZATION_DENIED, 403,
                GmErrorCode.COMMAND_NOT_FOUND, 404, GmErrorCode.COMMAND_ARGUMENT_MISMATCH, 400,
                GmErrorCode.COMMAND_REJECTED, 409, GmErrorCode.HANDLER_FAILED, 500,
                GmErrorCode.IDEMPOTENCY_CAPACITY_EXCEEDED, 429, GmErrorCode.IDEMPOTENCY_CONFLICT, 409,
                GmErrorCode.IDEMPOTENCY_IN_PROGRESS, 409, GmErrorCode.IDEMPOTENCY_FAILED, 409);
        statuses.forEach((code, expected) -> {
            var adapter = new GmRestTransportAdapter((a,b) -> GmOperationResponse.rejected(code, "t"), IDENTITY, 4096);
            var response = adapter.handle(req);
            assertEquals(expected, response.status());
            assertEquals(code.code(), JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8))
                    .getAsJsonObject().get("code").getAsString());
        });
    }

    @Test
    void businessRejectionInsideSuccessEnvelopeIsNotReportedAsSuccess() {
        var adapter = new GmRestTransportAdapter((a,b) -> GmOperationResponse.success(
                GmCommandExecutionResult.rejected("ping", "sensitive detail", Map.of("token", "secret")), "old-trace"),
                IDENTITY, 4096);
        var response = adapter.handle(request("{" + BASE + ",\"dryRun\":true}"));
        assertEquals(409, response.status());
        var output = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("ZERO-GM-COMMAND-REJECTED", output.get("code").getAsString());
        assertEquals("t", output.get("traceId").getAsString());
        assertTrue(output.getAsJsonObject("result").getAsJsonObject("data").isEmpty());
    }

    /** HTTP 失败仍保留已提交/未知业务事实及独立基础设施标记。 */
    @Test void preservesCommitFactsWhenBookkeepingOrAuditFails() {
        var committed = new GmOperationResponse(GmErrorCode.IDEMPOTENCY_STORE_FAILED.code(), "safe",
                GmCommandExecutionResult.success("ping", "private", Map.of()), "t",
                group.zn.zero.gm.GmBusinessCommitState.COMMITTED, false, true);
        var unknown = new GmOperationResponse(GmErrorCode.IDEMPOTENCY_IN_PROGRESS.code(), "safe", null, "t",
                group.zn.zero.gm.GmBusinessCommitState.UNKNOWN, false, false);
        var deniedAudit = new GmOperationResponse(GmErrorCode.OPERATION_AUTHORIZATION_DENIED.code(), "safe", null, "t",
                group.zn.zero.gm.GmBusinessCommitState.NOT_COMMITTED, true, false);
        var committedAudit = new GmOperationResponse(GmErrorCode.AUDIT_HOOK_FAILED.code(), "safe", null, "t",
                group.zn.zero.gm.GmBusinessCommitState.COMMITTED, true, false);
        for (var response : List.of(committed, unknown, deniedAudit, committedAudit)) {
            var adapter = new GmRestTransportAdapter((a, b) -> response, IDENTITY, 4096);
            var http = adapter.handle(request("{" + BASE + ",\"dryRun\":false}"));
            assertEquals(response == unknown ? 409 : response == deniedAudit ? 403 : 500, http.status());
            var body = JsonParser.parseString(new String(http.body(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(response.code(), body.get("code").getAsString());
            assertEquals(response.businessCommitState().name(), body.get("businessCommitState").getAsString());
            assertEquals(response.auditFailed(), body.get("auditFailed").getAsBoolean());
            assertEquals(response.idempotencyFailed(), body.get("idempotencyFailed").getAsBoolean());
        }
    }

    /** 真实 REST→endpoint→executor 链必须绑定目标并落实指令级审批。 */
    @Test void restCommandCannotBypassTargetOrCommandApproval() {
        var calls = new AtomicInteger();
        var registry = new GmCommandRegistry();
        registry.register(new GmCommandDefinition(List.of("grant"), List.of("uid"), "grant",
                GmCommandRisk.LOW, "uid", true), new GmCommandHandler() {
                    @Override public GmDryRunResult dryRun(GmCommandExecutionRequest request) {
                        calls.incrementAndGet();
                        return GmDryRunResult.accepted("grant", "preview", Map.of());
                    }
                    @Override public GmCommandExecutionResult execute(GmCommandExecutionRequest request) {
                        calls.incrementAndGet();
                        return GmCommandExecutionResult.success("grant", "ok", Map.of());
                    }
                });
        var policy = new GmOperationAuthorizationPolicy(Set.of(), Set.of(), Set.of("0.0.0.0/0", "::/0"), false, false);
        var endpoint = new GmOperationEndpoint(GmCommandExecutor.systemClock(registry, event -> { }),
                new GmOperationAuthorizer(policy, policy.sourceIpPolicy(),
                        (context, target, token) -> "approved-A".equals(token) && "A".equals(target)
                                ? GmApprovalDecision.APPROVED : GmApprovalDecision.INVALID, event -> { }));
        var adapter = new GmRestTransportAdapter((operation, metadata) -> endpoint.handle(operation), IDENTITY, 4096);
        for (boolean dryRun : List.of(false, true)) {
            var body = JsonParser.parseString("{" + BASE + "}").getAsJsonObject();
            body.addProperty("dryRun", dryRun);
            body.addProperty("target", "A");
            body.addProperty("command", "/grant B");
            body.addProperty("approvalToken", "approved-A");
            assertEquals(403, adapter.handle(request(body.toString())).status());
            body.addProperty("command", "/grant A");
            body.addProperty("approvalToken", "invalid");
            assertEquals(403, adapter.handle(request(body.toString())).status());
            assertEquals(dryRun ? 1 : 0, calls.get());
            body.addProperty("approvalToken", "approved-A");
            assertEquals(200, adapter.handle(request(body.toString())).status());
        }
        assertEquals(2, calls.get());
    }
}
