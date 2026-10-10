package group.zn.zero.gm.rest;

import com.google.gson.JsonObject;
import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.gm.GmCommandExecutionResult;
import group.zn.zero.gm.GmErrorCode;
import group.zn.zero.gm.GmOperationResponse;
import group.zn.zero.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** HTTP 结果映射与安全字段投影；不透传 handler 自由文本或未获准的数据。 @author zn */
final class GmRestResponses {
    private GmRestResponses() { }

    static HttpResponse error(final int status, final String code, final String message,
                              final String traceId, final String correlationId) {
        return json(status, envelope(code, message, traceId, correlationId));
    }

    static HttpResponse operation(final GmOperationResponse response, final String traceId,
                                  final String correlationId, final Set<String> visibleFields) {
        GmCommandExecutionResult result = response.result();
        ErrorCode error = result != null && !result.success() ? result.errorCode() : knownError(response.code());
        boolean success = error == null && "ZERO-OK".equals(response.code());
        String code = error != null ? error.code() : success ? "ZERO-OK" : SystemErrorCode.SYSTEM_ERROR.code();
        String message = error != null ? error.message() : success ? "success" : SystemErrorCode.SYSTEM_ERROR.message();
        JsonObject body = envelope(code, message, traceId, correlationId);
        body.addProperty("businessCommitState", response.businessCommitState().name());
        body.addProperty("auditFailed", response.auditFailed());
        body.addProperty("idempotencyFailed", response.idempotencyFailed());
        if (result != null) {
            JsonObject detail = new JsonObject();
            detail.addProperty("commandKey", result.commandKey());
            detail.addProperty("dryRun", result.dryRun());
            detail.addProperty("success", result.success());
            if (error != null) {
                detail.addProperty("errorCode", error.code());
            }
            JsonObject data = new JsonObject();
            visibleFields.stream().sorted().forEach(key -> {
                String value = result.data().get(key);
                if (value != null && value.length() <= 1024) data.addProperty(key, value);
            });
            detail.add("data", data);
            body.add("result", detail);
        }
        return json(success ? 200 : status(error), body);
    }

    private static ErrorCode knownError(final String code) {
        for (GmErrorCode value : GmErrorCode.values()) if (value.code().equals(code)) return value;
        return null;
    }

    private static int status(final ErrorCode code) {
        if (code == null) return 500;
        if (code == GmErrorCode.OPERATION_AUTHORIZATION_DENIED) return 403;
        if (code == GmErrorCode.COMMAND_NOT_FOUND) return 404;
        if (code == GmErrorCode.IDEMPOTENCY_CAPACITY_EXCEEDED) return 429;
        if (code == GmErrorCode.IDEMPOTENCY_IN_PROGRESS) return 409;
        if (code == GmErrorCode.IDEMPOTENCY_CONFLICT || code == GmErrorCode.IDEMPOTENCY_FAILED
                || code == GmErrorCode.COMMAND_REJECTED) return 409;
        return switch (code.category()) {
            case CLIENT_REQUEST, PROTOCOL -> 400;
            case PERMISSION -> 403;
            case SERVER_CALL, DATA_ACCESS, CACHE -> 503;
            default -> 500;
        };
    }

    private static JsonObject envelope(final String code, final String message,
                                       final String traceId, final String correlationId) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("message", message);
        body.addProperty("traceId", traceId);
        body.addProperty("correlationId", correlationId);
        return body;
    }

    private static HttpResponse json(final int status, final JsonObject body) {
        return new HttpResponse(status, Map.of("content-type", "application/json; charset=utf-8"),
                body.toString().getBytes(StandardCharsets.UTF_8));
    }
}
