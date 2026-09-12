package group.zn.zero.gm;

import java.time.Instant;
import java.util.Objects;

/** Immutable allow-listed representation of one GM audit event for persistence. */
public record GmAuditRecord(
        String eventId,
        String operationId,
        Instant time,
        GmAuditPhase phase,
        String traceId,
        String commandKey,
        int parameterCount,
        boolean dryRun,
        String result,
        GmBusinessCommitState businessCommitState,
        String errorCode,
        String safeMessage,
        String operatorRef,
        String sourceAddressRef,
        String targetType,
        String targetRef,
        String approvalRef,
        String approvalState,
        String structureFingerprint) {
    public GmAuditRecord {
        eventId = checked(eventId, "eventId");
        operationId = checked(operationId, "operationId");
        time = Objects.requireNonNull(time, "time");
        phase = Objects.requireNonNull(phase, "phase");
        traceId = checked(traceId, "traceId");
        commandKey = checked(commandKey, "commandKey");
        if (parameterCount < 0) {
            throw new IllegalArgumentException("parameterCount must not be negative");
        }
        result = checked(result, "result");
        businessCommitState = Objects.requireNonNull(businessCommitState, "businessCommitState");
        errorCode = errorCode == null ? "" : optionalChecked(errorCode, "errorCode");
        safeMessage = checked(safeMessage, "safeMessage");
        operatorRef = checked(operatorRef, "operatorRef");
        sourceAddressRef = checked(sourceAddressRef, "sourceAddressRef");
        targetType = checked(targetType, "targetType");
        targetRef = targetRef == null ? "" : checked(targetRef, "targetRef");
        approvalRef = approvalRef == null ? "" : checked(approvalRef, "approvalRef");
        approvalState = checked(approvalState, "approvalState");
        structureFingerprint = checked(structureFingerprint, "structureFingerprint");
    }

    /** Converts the already-safe event without retaining its mutable internals. */
    public static GmAuditRecord from(final GmAuditEvent event) {
        Objects.requireNonNull(event, "event");
        String operationId = event.requestFingerprint().isBlank()
                ? event.structureFingerprint() + "|" + event.traceId()
                : event.requestFingerprint();
        String eventId = event.time().toEpochMilli() + "|" + event.phase() + "|" + operationId;
        GmAuditAttribution attribution = event.attribution();
        return new GmAuditRecord(
                ref(eventId),
                ref(operationId),
                event.time(),
                event.phase(),
                event.traceId(),
                event.commandKey(),
                event.parameterCount(),
                event.dryRun(),
                event.result().name(),
                event.businessCommitState(),
                event.errorCode() == null ? "" : event.errorCode().code(),
                event.safeMessage(),
                attribution.operatorRef(),
                attribution.sourceAddressRef(),
                attribution.targetType(),
                attribution.targetRef(),
                attribution.approvalRef(),
                attribution.approvalState().name(),
                event.structureFingerprint());
    }

    private static String checked(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static String optionalChecked(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > 512 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static String ref(final String value) {
        return "audit-ref:" + Integer.toUnsignedString(value.hashCode(), 16);
    }
}
