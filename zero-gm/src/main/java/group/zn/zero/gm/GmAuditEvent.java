package group.zn.zero.gm;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.log.LogResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 不含原始身份、请求值或异常说明的 GM 审计事件。
 *
 * <p>事件只接受 {@link GmAuditAttributionFactory} 生成的安全归因，不持有
 * {@link GmCommandContext}、{@link GmCommandExecutionRequest}、raw command、参数值、
 * 原 target、roles、permissions、attributes 或原异常 message。</p>
 *
 * @author zn
 */
public final class GmAuditEvent {

    /** 事件固定标识最大长度。 */
    private static final int MAX_IDENTIFIER_LENGTH = 128;

    /** 安全说明最大长度。 */
    private static final int MAX_SAFE_MESSAGE_LENGTH = 256;

    /** 审计时间。 */
    private final Instant time;

    /** 审计阶段。 */
    private final GmAuditPhase phase;

    /** 已安全转换的审计归因。 */
    private final GmAuditAttribution attribution;

    /** 显式 TraceId。 */
    private final String traceId;

    /** 指令 key。 */
    private final String commandKey;

    /** 有序参数名白名单，不包含参数值。 */
    private final List<String> parameterNames;

    /** 参数数量。 */
    private final int parameterCount;

    /** 是否为 dry-run。 */
    private final boolean dryRun;

    /** 统一日志结果。 */
    private final LogResult result;

    /** 业务提交状态。 */
    private final GmBusinessCommitState businessCommitState;

    /** 非成功事件的真实 ErrorCode；started / success 时为空。 */
    private final ErrorCode errorCode;

    /** 不含业务返回值或异常 message 的安全说明。 */
    private final String safeMessage;

    /** 不含参数值的结构指纹。 */
    private final String structureFingerprint;

    /** 可选的完整请求 HMAC 关联指纹；未配置密钥时为空字符串。 */
    private final String requestFingerprint;

    GmAuditEvent(
            final Instant time,
            final GmAuditPhase phase,
            final GmAuditAttribution attribution,
            final String traceId,
            final String commandKey,
            final List<String> parameterNames,
            final int parameterCount,
            final boolean dryRun,
            final LogResult result,
            final GmBusinessCommitState businessCommitState,
            final ErrorCode errorCode,
            final String safeMessage,
            final String structureFingerprint,
            final String requestFingerprint) {
        this.time = Objects.requireNonNull(time, "time");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.attribution = Objects.requireNonNull(attribution, "attribution");
        this.traceId = checkedIdentifier(traceId, "traceId");
        this.commandKey = checkedIdentifier(commandKey, "commandKey");
        this.parameterNames = copyParameterNames(parameterNames);
        this.parameterCount = parameterCount;
        this.dryRun = dryRun;
        this.result = Objects.requireNonNull(result, "result");
        this.businessCommitState = Objects.requireNonNull(businessCommitState, "businessCommitState");
        this.errorCode = errorCode;
        this.safeMessage = checkedText(safeMessage, MAX_SAFE_MESSAGE_LENGTH, false, "safeMessage");
        this.structureFingerprint = checkedText(
                structureFingerprint,
                MAX_IDENTIFIER_LENGTH,
                false,
                "structureFingerprint");
        this.requestFingerprint = checkedText(
                requestFingerprint,
                256,
                true,
                "requestFingerprint");
        validateParameterCount();
        validateResultErrorCode();
        validatePhaseAndCommitState();
    }

    /**
     * 返回审计时间。
     *
     * @return 审计时间；不可为空；不可变且线程安全。
     */
    public Instant time() {
        return time;
    }

    /**
     * 返回审计阶段。
     *
     * @return 审计阶段；不可为空；线程安全。
     */
    public GmAuditPhase phase() {
        return phase;
    }

    /**
     * 返回已安全转换的归因。
     *
     * @return 安全归因；不可为空；不可变且线程安全。
     */
    public GmAuditAttribution attribution() {
        return attribution;
    }

    /**
     * 返回显式 TraceId。
     *
     * @return TraceId；不可为空或空白；线程安全。
     */
    public String traceId() {
        return traceId;
    }

    /**
     * 返回指令 key。
     *
     * @return 指令 key；不可为空或空白；线程安全。
     */
    public String commandKey() {
        return commandKey;
    }

    /**
     * 返回有序参数名白名单。
     *
     * @return 参数名列表；不可为空；有序、不可变、可能为空且线程安全；不含参数值。
     */
    public List<String> parameterNames() {
        return parameterNames;
    }

    /**
     * 返回参数数量。
     *
     * @return 非负参数数量；与 {@link #parameterNames()} 大小一致；线程安全。
     */
    public int parameterCount() {
        return parameterCount;
    }

    /**
     * 返回当前事件是否属于 dry-run。
     *
     * @return dry-run 事件时为 true；线程安全。
     */
    public boolean dryRun() {
        return dryRun;
    }

    /**
     * 返回统一日志结果。
     *
     * @return 日志结果；不可为空；线程安全。
     */
    public LogResult result() {
        return result;
    }

    /**
     * 返回业务提交状态。
     *
     * @return 四态业务提交状态；不可为空；线程安全。
     */
    public GmBusinessCommitState businessCommitState() {
        return businessCommitState;
    }

    /**
     * 返回非成功事件的真实 ErrorCode。
     *
     * @return failure / rejected 时不可为空，started / success 时为空；线程安全。
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 返回安全说明。
     *
     * @return 不含 handler 返回内容或原异常 message 的受控说明；不可为空；线程安全。
     */
    public String safeMessage() {
        return safeMessage;
    }

    /**
     * 返回不含参数值的结构指纹。
     *
     * @return 结构指纹；不可为空；线程安全。
     */
    public String structureFingerprint() {
        return structureFingerprint;
    }

    /**
     * 返回可选请求 HMAC 关联指纹。
     *
     * @return 带算法和 keyId 的 HMAC 指纹；未配置密钥时为空字符串；线程安全。
     */
    public String requestFingerprint() {
        return requestFingerprint;
    }

    /**
     * 返回目标类型。
     *
     * @return 安全目标类型；不可为空；线程安全。
     */
    public String targetType() {
        return attribution.targetType();
    }

    /**
     * 返回目标安全引用。
     *
     * @return 安全引用；没有目标值时为空字符串；线程安全。
     */
    public String targetRef() {
        return attribution.targetRef();
    }

    /**
     * 返回不包含安全引用值、请求内容或密钥的诊断字符串。
     *
     * @return 有界诊断文本；不可为空；线程安全。
     */
    @Override
    public String toString() {
        return "GmAuditEvent{phase=" + phase
                + ", commandKey='" + commandKey + '\''
                + ", dryRun=" + dryRun
                + ", result=" + result
                + ", businessCommitState=" + businessCommitState
                + ", errorCode=" + (errorCode == null ? "none" : errorCode.code())
                + '}';
    }

    private void validateParameterCount() {
        if (parameterCount < 0 || parameterCount != parameterNames.size()) {
            throw new IllegalArgumentException("gm audit parameter count is invalid");
        }
    }

    private void validateResultErrorCode() {
        boolean requiresErrorCode = result == LogResult.FAILURE || result == LogResult.REJECTED;
        if (requiresErrorCode != (errorCode != null)
                || errorCode != null && SystemErrorCode.OK.code().equals(errorCode.code())) {
            throw new IllegalArgumentException("gm audit result and errorCode conflict");
        }
        if (result == LogResult.TIMEOUT || result == LogResult.DEGRADED) {
            throw new IllegalArgumentException("unsupported gm audit result");
        }
    }

    private void validatePhaseAndCommitState() {
        boolean dryRunPhase = phase == GmAuditPhase.BEFORE_DRY_RUN
                || phase == GmAuditPhase.AFTER_DRY_RUN
                || phase == GmAuditPhase.DRY_RUN_FAILED;
        if (dryRun != dryRunPhase) {
            throw new IllegalArgumentException("gm audit phase and dryRun conflict");
        }
        if (dryRun) {
            validateDryRunState();
            return;
        }
        validateExecuteState();
    }

    private void validateDryRunState() {
        if (businessCommitState != GmBusinessCommitState.NOT_APPLICABLE) {
            throw new IllegalArgumentException("dry-run must not have a business commit state");
        }
        if (phase == GmAuditPhase.BEFORE_DRY_RUN && result != LogResult.STARTED) {
            throw new IllegalArgumentException("before dry-run audit must be started");
        }
        if (phase == GmAuditPhase.AFTER_DRY_RUN
                && result != LogResult.SUCCESS
                && result != LogResult.REJECTED) {
            throw new IllegalArgumentException("after dry-run audit result is invalid");
        }
        if (phase == GmAuditPhase.DRY_RUN_FAILED && result != LogResult.FAILURE) {
            throw new IllegalArgumentException("failed dry-run audit must be failure");
        }
    }

    private void validateExecuteState() {
        if (phase == GmAuditPhase.BEFORE_EXECUTE
                && (result != LogResult.STARTED
                || businessCommitState != GmBusinessCommitState.NOT_COMMITTED)) {
            throw new IllegalArgumentException("before execute audit state is invalid");
        }
        if (phase == GmAuditPhase.AFTER_EXECUTE) {
            boolean success = result == LogResult.SUCCESS
                    && businessCommitState == GmBusinessCommitState.COMMITTED;
            boolean rejected = result == LogResult.REJECTED
                    && businessCommitState == GmBusinessCommitState.NOT_COMMITTED;
            if (!success && !rejected) {
                throw new IllegalArgumentException("after execute audit state is invalid");
            }
        }
        if (phase == GmAuditPhase.EXECUTE_FAILED
                && (result != LogResult.FAILURE
                || businessCommitState != GmBusinessCommitState.UNKNOWN)) {
            throw new IllegalArgumentException("failed execute audit state is invalid");
        }
    }

    private static String checkedIdentifier(final String value, final String name) {
        return checkedText(value, MAX_IDENTIFIER_LENGTH, false, name);
    }

    private static String checkedText(
            final String value,
            final int maxLength,
            final boolean allowEmpty,
            final String name) {
        Objects.requireNonNull(value, name);
        if ((!allowEmpty && value.isBlank()) || value.length() > maxLength || containsControlCharacter(value)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static List<String> copyParameterNames(final List<String> source) {
        Objects.requireNonNull(source, "parameterNames");
        List<String> copied = new ArrayList<>(source.size());
        for (String parameterName : source) {
            copied.add(checkedText(parameterName, 64, false, "parameterName"));
        }
        return List.copyOf(copied);
    }

    private static boolean containsControlCharacter(final String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < 0x20 || (current >= 0x7F && current <= 0x9F)) {
                return true;
            }
        }
        return false;
    }
}
