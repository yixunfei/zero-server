package group.zn.zero.gm;

import java.util.Objects;

/** Redacted audit event emitted when a GM operation is denied. */
public record GmAuthorizationAuditEvent(
        String operationRef,
        String operatorRef,
        String sourceRef,
        String targetRef,
        GmAuthorizationFailureReason reason) {
    public GmAuthorizationAuditEvent {
        operationRef = Objects.requireNonNull(operationRef, "operationRef");
        operatorRef = Objects.requireNonNull(operatorRef, "operatorRef");
        sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
        targetRef = Objects.requireNonNull(targetRef, "targetRef");
        reason = Objects.requireNonNull(reason, "reason");
    }
}
