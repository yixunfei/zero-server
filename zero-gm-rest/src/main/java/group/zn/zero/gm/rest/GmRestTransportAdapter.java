package group.zn.zero.gm.rest;

import group.zn.zero.gm.GmCommandContext;
import group.zn.zero.gm.GmIdentityProvider;
import group.zn.zero.gm.GmOperationRequest;
import group.zn.zero.gm.GmOperationResponse;
import group.zn.zero.gm.GmTransportAdapter;
import group.zn.zero.gm.GmTransportMetadata;
import group.zn.zero.gm.GmTransportRequest;
import group.zn.zero.net.http.HttpRequest;
import group.zn.zero.net.http.HttpResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;

/** 有界 GM REST 适配器；身份、授权与审计由注入端口处理。 @author zn */
public final class GmRestTransportAdapter {
    /** 默认请求字节预算。 */
    public static final int DEFAULT_MAX_BODY_BYTES = 64 * 1024;
    /** 已有 GM 授权、执行和审计链。 */
    private final GmTransportAdapter delegate;
    /** 应用提供的可信身份解析器。 */
    private final GmIdentityProvider identityProvider;
    /** UTF-8 请求预算。 */
    private final int maxBodyBytes;
    /** 应用确认已脱敏的结果键白名单；不可变。 */
    private final Set<String> visibleResultFields;

    /**
     * 创建默认隐藏自由结果数据的适配器；构造无外部副作用。
     * @param delegate GM 端口。 @param identityProvider 身份端口。 @param maxBodyBytes 正数预算。
     * @throws IllegalArgumentException 非正预算。 @throws NullPointerException 空端口。
     */
    public GmRestTransportAdapter(final GmTransportAdapter delegate,
                                 final GmIdentityProvider identityProvider, final int maxBodyBytes) {
        this(delegate, identityProvider, maxBodyBytes, Set.of());
    }

    /**
     * 创建允许展示安全结果字段的适配器；只复制配置，可并发使用。
     * @param delegate 线程安全 GM 端口。 @param identityProvider 线程安全可信身份端口。
     * @param maxBodyBytes 正数预算。 @param visibleResultFields 白名单，应用确保值已脱敏；最多 32 项。
     * @throws IllegalArgumentException 预算或白名单非法。 @throws NullPointerException 参数为空。
     */
    public GmRestTransportAdapter(final GmTransportAdapter delegate, final GmIdentityProvider identityProvider,
                                 final int maxBodyBytes, final Set<String> visibleResultFields) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        if (maxBodyBytes <= 0) throw new IllegalArgumentException("maxBodyBytes must be positive");
        this.maxBodyBytes = maxBodyBytes;
        this.visibleResultFields = Set.copyOf(visibleResultFields);
        if (this.visibleResultFields.size() > 32 || this.visibleResultFields.stream()
                .anyMatch(key -> !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"))) {
            throw new IllegalArgumentException("invalid result field allowlist");
        }
    }

    /** @param delegate GM 端口。 @return 拒绝全部身份的适配器，无资源副作用，线程安全。 */
    public static GmRestTransportAdapter failClosed(final GmTransportAdapter delegate) {
        return new GmRestTransportAdapter(delegate, GmIdentityProvider.failClosed(), DEFAULT_MAX_BODY_BYTES);
    }

    /**
     * 处理 POST /gm/operation；显式 boolean dryRun 必填，解析失败不调用身份或执行端口。
     * @param request 不可为空。 @return JSON 响应，含追踪字段；线程安全取决于注入端口。
     * @throws NullPointerException 请求为空。数据变更由 dryRun 和已有 GM 授权链决定。
     */
    public HttpResponse handle(final HttpRequest request) {
        Objects.requireNonNull(request, "request");
        if (!"/gm/operation".equals(request.uri())) {
            return error(404, "ZERO-GM-ROUTE-NOT-FOUND", "gm route not found", null);
        }
        if (!"POST".equalsIgnoreCase(request.method())) {
            return error(405, "ZERO-GM-METHOD-NOT-ALLOWED", "POST required", null);
        }
        byte[] body = request.body();
        if (body.length > maxBodyBytes) return error(413, "ZERO-GM-BODY-TOO-LARGE", "gm body too large", null);
        GmRestRequest parsed;
        try {
            parsed = GmRestRequest.parse(body);
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
            return error(400, "ZERO-GM-REQUEST-INVALID", "invalid JSON fields; explicit boolean dryRun required", null);
        }
        GmCommandContext context;
        try {
            context = identityProvider.resolve(new GmTransportRequest(
                    request.method(), request.uri(), request.headers(), parsed.text()));
        } catch (IllegalArgumentException failure) {
            return error(401, "ZERO-GM-IDENTITY-REQUIRED", "authenticated identity required", parsed);
        }
        if (context == null) return error(401, "ZERO-GM-IDENTITY-REQUIRED", "authenticated identity required", parsed);
        if (!parsed.traceId().equals(context.traceId())) {
            return error(400, "ZERO-GM-METADATA-MISMATCH", "gm metadata mismatch", parsed);
        }
        GmOperationResponse result = delegate.handle(new GmOperationRequest(context, parsed.command(), parsed.target(),
                parsed.approvalToken(), parsed.secondReviewer(), parsed.idempotencyKey(), parsed.dryRun()),
                new GmTransportMetadata(parsed.traceId(), parsed.correlationId(), parsed.idempotencyKey(),
                        context.operatorIp(), context.attributes(), true));
        return GmRestResponses.operation(Objects.requireNonNull(result, "GM response"),
                parsed.traceId(), parsed.correlationId(), visibleResultFields);
    }

    private HttpResponse error(final int status, final String code, final String message, final GmRestRequest request) {
        return GmRestResponses.error(status, code, message,
                request == null ? "" : request.traceId(), request == null ? "" : request.correlationId());
    }
}
