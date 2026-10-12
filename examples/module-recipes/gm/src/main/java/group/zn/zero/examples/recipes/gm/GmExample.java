package group.zn.zero.examples.recipes.gm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import group.zn.zero.gm.GmApprovalDecision;
import group.zn.zero.gm.GmAuditEvent;
import group.zn.zero.gm.GmAuditPhase;
import group.zn.zero.gm.GmAuthorizationAuditEvent;
import group.zn.zero.gm.GmCommandContext;
import group.zn.zero.gm.GmCommandDefinition;
import group.zn.zero.gm.GmCommandExecutor;
import group.zn.zero.gm.GmCommandRegistry;
import group.zn.zero.gm.GmCommandRisk;
import group.zn.zero.gm.GmIdentityProvider;
import group.zn.zero.gm.GmOperationAuthorizationPolicy;
import group.zn.zero.gm.GmOperationAuthorizer;
import group.zn.zero.gm.GmOperationEndpoint;
import group.zn.zero.gm.InMemoryGmIdempotencyStore;
import group.zn.zero.gm.rest.GmRestTransportAdapter;
import group.zn.zero.net.http.HttpRequest;
import group.zn.zero.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存 HTTP 请求 → REST 适配器 → 身份/授权/审批 → 幂等 → 审计及业务。
 * 固定身份和审批仅为本地 fixture；不监听端口，不提供可部署的认证服务。
 * @author zn
 */
public final class GmExample {
    private GmExample() { }

    /** @param args 未使用；隔离运行并打印安全摘要，不输出身份或审批凭证。 */
    public static void main(final String[] args) {
        System.out.println("gm=" + runFlow());
    }

    /**
     * 演示预演、执行、重放、幂等冲突、目标/审批/权限拒绝及审计。
     * 每次调用独占全部内存组件；结果可跨线程读取。
     * @return 非空不可变摘要，无个人信息、凭证或可变业务引用。
     * @throws RuntimeException 非预期适配器或结果解析失败，保留给宿主异常边界。
     */
    public static Result runFlow() {
        var command = new AnnouncementCommand();
        var registry = new GmCommandRegistry();
        registry.register(new GmCommandDefinition(List.of("announce"), List.of("server", "text"),
                "本地公告演示", GmCommandRisk.MEDIUM, "server", true), command);
        var audits = new CopyOnWriteArrayList<GmAuditEvent>();
        var denials = new CopyOnWriteArrayList<GmAuthorizationAuditEvent>();
        var policy = new GmOperationAuthorizationPolicy(Set.of("operator"), Set.of("gm.announce"),
                Set.of("127.0.0.1/32"), true, false);
        var authorizer = new GmOperationAuthorizer(policy, policy.sourceIpPolicy(),
                (context, target, token) -> "server-local".equals(target) && "fixture-approved".equals(token)
                        ? GmApprovalDecision.APPROVED : GmApprovalDecision.INVALID, denials::add);
        var endpoint = new GmOperationEndpoint(GmCommandExecutor.systemClock(registry, audits::add),
                authorizer, new InMemoryGmIdempotencyStore());
        var adapter = adapter(endpoint, Set.of("gm.announce"));
        var preview = adapter.handle(request(true, "preview", "server-local", "fixture-approved", "hello"));
        int afterPreview = command.executions();
        var executeRequest = request(false, "operation-001", "server-local", "fixture-approved", "hello");
        var executed = adapter.handle(executeRequest);
        var replayed = adapter.handle(executeRequest);
        var conflict = adapter.handle(request(false, "operation-001", "server-local", "fixture-approved", "changed"));
        var mismatch = adapter.handle(request(false, "mismatch", "another-server", "fixture-approved", "hello"));
        var unapproved = adapter.handle(request(false, "no-approval", "server-local", "invalid", "hello"));
        var forbidden = adapter(endpoint, Set.of()).handle(
                request(false, "no-permission", "server-local", "fixture-approved", "hello"));
        var unauthenticated = GmRestTransportAdapter.failClosed((operation, metadata) -> endpoint.handle(operation))
                .handle(executeRequest);
        var phases = audits.stream().map(GmAuditEvent::phase).toList();
        var response = JsonParser.parseString(new String(executed.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        return new Result(preview.status(), afterPreview, executed.status(), replayed.status(), conflict.status(),
                mismatch.status(), unapproved.status(), forbidden.status(), unauthenticated.status(), command.executions(),
                phases, denials.size(), response.get("businessCommitState").getAsString(),
                !response.toString().contains("internalNote"));
    }

    private static GmRestTransportAdapter adapter(final GmOperationEndpoint endpoint, final Set<String> permissions) {
        // 身份来自组合根固定 fixture，绝不相信请求 body/header 中自声明的角色、IP 或权限。
        GmIdentityProvider identity = request -> new GmCommandContext("fixture-operator", "127.0.0.1",
                "trace-gm", Set.of("operator"), permissions, "", "", Map.of());
        return new GmRestTransportAdapter((operation, metadata) -> endpoint.handle(operation), identity, 4096, Set.of("count"));
    }

    private static HttpRequest request(final boolean dryRun, final String key, final String declaredTarget,
                                       final String approval, final String message) {
        var body = new JsonObject();
        body.addProperty("traceId", "trace-gm");
        body.addProperty("correlationId", "correlation-" + key);
        body.addProperty("idempotencyKey", key);
        body.addProperty("command", "/announce server-local " + message);
        body.addProperty("target", declaredTarget);
        body.addProperty("approvalToken", approval);
        body.addProperty("dryRun", dryRun);
        return new HttpRequest("POST", "/gm/operation", Map.of(), body.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 不可变安全摘要，线程安全；审计列表有序、不可变、可为空。
     * @param previewStatus 预演 HTTP 状态。
     * @param afterPreview 预演后的执行次数。
     * @param executeStatus 执行状态。
     * @param replayStatus 重放状态。
     * @param conflictStatus 同键异参状态。
     * @param mismatchStatus 目标不匹配状态。
     * @param unapprovedStatus 审批不通过状态。
     * @param forbiddenStatus 权限不足状态。
     * @param unauthenticatedStatus 身份缺失状态。
     * @param executions 最终实际执行次数。
     * @param phases 命令审计阶段。
     * @param denials 授权拒绝审计数。
     * @param commitState 执行响应的业务提交事实。
     * @param hiddenInternalField 非白名单自由字段是否隐藏。
     * @author zn
     */
    public record Result(int previewStatus, int afterPreview, int executeStatus, int replayStatus,
                         int conflictStatus, int mismatchStatus, int unapprovedStatus, int forbiddenStatus,
                         int unauthenticatedStatus, int executions, List<GmAuditPhase> phases, int denials,
                         String commitState, boolean hiddenInternalField) {
        /** 防御性复制审计阶段，null 列表抛出 NullPointerException。 */
        public Result { phases = List.copyOf(phases); }
    }
}
