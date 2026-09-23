package group.zn.zero.gm;

import java.util.Objects;

/** Transport-neutral GM operation adapter port for RPC implementations. */
public interface GmRpcTransportAdapter {
    GmRpcResponse handle(GmRpcRequest request);

    record GmRpcRequest(
            String correlationId,
            String traceId,
            String payload,
            boolean oneway) {
        public GmRpcRequest {
            text(correlationId, "correlationId");
            text(traceId, "traceId");
            text(payload, "payload");
            if (payload.length() > 64 * 1024) {
                throw new IllegalArgumentException("payload is too large");
            }
        }

        private static void text(final String value, final String name) {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(name + " is invalid");
            }
        }
    }

    record GmRpcResponse(String correlationId, String traceId, String code, String message) {
        public GmRpcResponse {
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(traceId, "traceId");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }
}
