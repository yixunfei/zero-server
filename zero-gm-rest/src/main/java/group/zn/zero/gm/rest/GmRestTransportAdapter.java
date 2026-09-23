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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Minimal bounded REST boundary for an application-provided GM transport. */
public final class GmRestTransportAdapter {
    public static final int DEFAULT_MAX_BODY_BYTES = 64 * 1024;
    private final GmTransportAdapter delegate;
    private final GmIdentityProvider identityProvider;
    private final int maxBodyBytes;

    /** Creates a fail-closed-by-construction bounded adapter. */
    public GmRestTransportAdapter(final GmTransportAdapter delegate,
                                  final GmIdentityProvider identityProvider,
                                  final int maxBodyBytes) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes must be positive");
        }
        this.maxBodyBytes = maxBodyBytes;
    }

    /** Creates an adapter that rejects requests unless an application identity provider is supplied later. */
    public static GmRestTransportAdapter failClosed(final GmTransportAdapter delegate) {
        return new GmRestTransportAdapter(delegate, GmIdentityProvider.failClosed(), DEFAULT_MAX_BODY_BYTES);
    }

    /** Handles only POST /gm/operation and a bounded, deliberately small JSON field object. */
    public HttpResponse handle(final HttpRequest request) {
        Objects.requireNonNull(request, "request");
        if (!"POST".equalsIgnoreCase(request.method()) || !"/gm/operation".equals(request.uri())) {
            return response(404, "ZERO-GM-ROUTE-NOT-FOUND", "gm route not found");
        }
        byte[] body = request.body();
        if (body.length > maxBodyBytes) {
            return response(413, "ZERO-GM-BODY-TOO-LARGE", "gm request body too large");
        }
        try {
            String text = new String(body, StandardCharsets.UTF_8);
            Map<String, String> fields = parseFields(text);
            String traceId = required(fields, "traceId");
            String correlationId = required(fields, "correlationId");
            String idempotencyKey = required(fields, "idempotencyKey");
            String command = required(fields, "command");
            String target = required(fields, "target");
            GmTransportRequest transportRequest = new GmTransportRequest(request.method(), request.uri(), request.headers(), text);
            GmCommandContext context = Objects.requireNonNull(identityProvider.resolve(transportRequest), "identity context");
            if (!traceId.equals(context.traceId())) {
                return response(400, "ZERO-GM-METADATA-MISMATCH", "gm metadata mismatch");
            }
            GmOperationResponse result = delegate.handle(new GmOperationRequest(context, command, target,
                    fields.getOrDefault("approvalToken", ""), fields.getOrDefault("secondReviewer", ""),
                    Boolean.parseBoolean(fields.getOrDefault("dryRun", "false"))),
                    new GmTransportMetadata(traceId, correlationId, idempotencyKey,
                            context.operatorIp(), context.attributes(), true));
            return response(result.code().startsWith("ZERO-OK") ? 200 : 403, result.code(), result.message());
        } catch (IllegalArgumentException | NullPointerException exception) {
            return response(400, "ZERO-GM-REQUEST-INVALID", "gm request rejected");
        }
    }

    private Map<String, String> parseFields(final String text) {
        if (text.isBlank() || text.length() > maxBodyBytes || !text.startsWith("{") || !text.endsWith("}")) {
            throw new IllegalArgumentException("invalid body");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        String content = text.substring(1, text.length() - 1).trim();
        if (content.isBlank()) throw new IllegalArgumentException("empty body");
        for (String part : content.split(",")) {
            String[] pair = part.split(":", 2);
            if (pair.length != 2) throw new IllegalArgumentException("invalid field");
            String key = strip(pair[0]);
            String value = strip(pair[1]);
            if (key.isBlank() || fields.put(key, value) != null) throw new IllegalArgumentException("duplicate field");
        }
        return Map.copyOf(fields);
    }

    private String required(final Map<String, String> fields, final String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank() || value.length() > 256) throw new IllegalArgumentException("missing field");
        return value;
    }

    private String strip(final String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        throw new IllegalArgumentException("field must be quoted");
    }

    private HttpResponse response(final int status, final String code, final String message) {
        String body = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
        return new HttpResponse(status, Map.of("content-type", "application/json; charset=utf-8"),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
