package group.zn.zero.gm;

import java.util.Objects;

/** Transport-neutral result returned by a GM operation entry point. */
public record GmOperationResponse(
        String code,
        String message,
        GmCommandExecutionResult result,
        String traceId) {
    public GmOperationResponse {
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
        traceId = Objects.requireNonNull(traceId, "traceId");
    }

    /** Builds a success response without exposing raw command data. */
    public static GmOperationResponse success(final GmCommandExecutionResult result, final String traceId) {
        return new GmOperationResponse("ZERO-OK", "success", result, traceId);
    }

    /** Builds a stable rejection response. */
    public static GmOperationResponse rejected(final GmErrorCode code, final String traceId) {
        return new GmOperationResponse(code.code(), code.message(), null, traceId);
    }
}
