package group.zn.zero.gm;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.log.LogResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * GM 指令执行器。
 *
 * <p>执行器同步生成构造前安全归因，记录前置与完成审计，并用
 * {@link GmBusinessCommitState} 区分不可提交、已提交和未知状态。执行器不创建线程、
 * 不自动重试，也不把上下文、raw command、参数值或异常 message 放入审计事件。</p>
 *
 * @author zn
 */
public final class GmCommandExecutor {

    /** GM 指令注册表。 */
    private final GmCommandRegistry registry;

    /** GM 审计 hook。 */
    private final GmAuditHook auditHook;

    /** 构造前安全归因工厂。 */
    private final GmAuditAttributionFactory attributionFactory;

    /** 安全结构与请求指纹策略。 */
    private final GmAuditFingerprintPolicy fingerprintPolicy;

    /** 时间来源。 */
    private final Clock clock;

    /**
     * 创建 GM 指令执行器。
     *
     * @param registry GM 指令注册表；不可为空。
     * @param auditHook GM 审计 hook；不可为空。
     * @param attributionFactory 构造前安全归因工厂；不可为空。
     * @param fingerprintPolicy 安全指纹策略；不可为空。
     * @param clock 时间来源；不可为空。
     * @throws NullPointerException 当任一依赖为空时抛出。
     */
    public GmCommandExecutor(
            final GmCommandRegistry registry,
            final GmAuditHook auditHook,
            final GmAuditAttributionFactory attributionFactory,
            final GmAuditFingerprintPolicy fingerprintPolicy,
            final Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.auditHook = Objects.requireNonNull(auditHook, "auditHook");
        this.attributionFactory = Objects.requireNonNull(attributionFactory, "attributionFactory");
        this.fingerprintPolicy = Objects.requireNonNull(fingerprintPolicy, "fingerprintPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 使用系统 UTC 时间、固定脱敏归因和仅结构指纹创建执行器。
     *
     * @param registry GM 指令注册表；不可为空。
     * @param auditHook GM 审计 hook；不可为空。
     * @return GM 指令执行器；不可为空；无数据变更；线程安全性取决于注册表和 hook 实现。
     * @throws NullPointerException 当注册表或审计 hook 为空时抛出。
     */
    public static GmCommandExecutor systemClock(
            final GmCommandRegistry registry,
            final GmAuditHook auditHook) {
        return new GmCommandExecutor(
                registry,
                auditHook,
                GmAuditAttributionFactory.redacted(),
                GmAuditFingerprintPolicy.structureOnly(),
                Clock.systemUTC());
    }

    /**
     * 使用系统 UTC 时间和调用方明确配置的安全策略创建执行器。
     *
     * @param registry GM 指令注册表；不可为空。
     * @param auditHook GM 审计 hook；不可为空。
     * @param attributionFactory 构造前安全归因工厂；不可为空。
     * @param fingerprintPolicy 安全指纹策略；不可为空。
     * @return GM 指令执行器；不可为空；无数据变更；线程安全性取决于依赖实现。
     * @throws NullPointerException 当任一依赖为空时抛出。
     */
    public static GmCommandExecutor systemClock(
            final GmCommandRegistry registry,
            final GmAuditHook auditHook,
            final GmAuditAttributionFactory attributionFactory,
            final GmAuditFingerprintPolicy fingerprintPolicy) {
        return new GmCommandExecutor(
                registry,
                auditHook,
                attributionFactory,
                fingerprintPolicy,
                Clock.systemUTC());
    }

    /**
     * 执行 GM dry-run 预演。
     *
     * <p>本方法不会调用正式执行 handler；所有审计事件的业务提交状态固定为
     * {@link GmBusinessCommitState#NOT_APPLICABLE}。handler 原异常保持主异常，失败审计异常只作为
     * suppressed；审计 hook 失败以 {@link GmAuditFailureException} 暴露。</p>
     *
     * @param context GM 操作上下文；不可为空。
     * @param rawText 原始 DSL 文本；不可为空，仅供解析和 handler 使用，不进入审计事件。
     * @return dry-run 统一执行结果；不可为空；data Map 无序、不可变、可能为空且线程安全。
     * @throws NullPointerException 当上下文或 DSL 文本为空时抛出。
     * @throws ZeroException 当 DSL、解析结果、handler 结果或审计失败时抛出。
     * @throws RuntimeException 当 handler 抛出非 ZeroException 时原样向上抛出。
     */
    public GmCommandExecutionResult dryRun(final GmCommandContext context, final String rawText) {
        GmCommandResolution resolution = resolve(rawText);
        GmCommandExecutionRequest request = resolution.toRequest(Objects.requireNonNull(context, "context"));
        AuditMetadata metadata = prepareMetadata(
                request,
                GmAuditPhase.BEFORE_DRY_RUN,
                GmBusinessCommitState.NOT_APPLICABLE);
        appendAudit(
                metadata,
                GmAuditPhase.BEFORE_DRY_RUN,
                true,
                LogResult.STARTED,
                GmBusinessCommitState.NOT_APPLICABLE,
                null,
                "gm dry-run started");
        GmDryRunResult dryRunResult;
        try {
            dryRunResult = validateDryRunResult(request, resolution.handler().dryRun(request));
        } catch (RuntimeException ex) {
            appendFailureSafely(metadata, GmAuditPhase.DRY_RUN_FAILED, true, ex);
            throw ex;
        }
        GmCommandExecutionResult result = GmCommandExecutionResult.fromDryRun(dryRunResult);
        appendAudit(
                metadata,
                GmAuditPhase.AFTER_DRY_RUN,
                true,
                result.success() ? LogResult.SUCCESS : LogResult.REJECTED,
                GmBusinessCommitState.NOT_APPLICABLE,
                result.errorCode(),
                result.success() ? "gm dry-run succeeded" : "gm dry-run rejected");
        return result;
    }

    /**
     * 正式执行 GM 指令。
     *
     * <p>前置审计失败时 handler 不会被调用，状态为 NOT_COMMITTED；正常成功返回为 COMMITTED；
     * handler 明确无副作用拒绝为 NOT_COMMITTED；handler 抛错、返回空值或非法结果为 UNKNOWN。
     * 成功后的后置审计失败会以 COMMITTED 暴露，调用方不得自动重试。</p>
     *
     * @param context GM 操作上下文；不可为空。
     * @param rawText 原始 DSL 文本；不可为空，仅供解析和 handler 使用，不进入审计事件。
     * @return 正式执行结果；不可为空；data Map 无序、不可变、可能为空且线程安全。
     * @throws NullPointerException 当上下文或 DSL 文本为空时抛出。
     * @throws ZeroException 当 DSL、解析结果、handler 结果或审计失败时抛出。
     * @throws RuntimeException 当 handler 抛出非 ZeroException 时原样向上抛出。
     */
    public GmCommandExecutionResult execute(final GmCommandContext context, final String rawText) {
        GmCommandResolution resolution = resolve(rawText);
        GmCommandExecutionRequest request = resolution.toRequest(Objects.requireNonNull(context, "context"));
        AuditMetadata metadata = prepareMetadata(
                request,
                GmAuditPhase.BEFORE_EXECUTE,
                GmBusinessCommitState.NOT_COMMITTED);
        appendAudit(
                metadata,
                GmAuditPhase.BEFORE_EXECUTE,
                false,
                LogResult.STARTED,
                GmBusinessCommitState.NOT_COMMITTED,
                null,
                "gm execute started");
        GmCommandExecutionResult result;
        try {
            result = validateExecutionResult(request, resolution.handler().execute(request));
        } catch (RuntimeException ex) {
            appendFailureSafely(metadata, GmAuditPhase.EXECUTE_FAILED, false, ex);
            throw ex;
        }
        GmBusinessCommitState commitState = result.success()
                ? GmBusinessCommitState.COMMITTED
                : GmBusinessCommitState.NOT_COMMITTED;
        appendAudit(
                metadata,
                GmAuditPhase.AFTER_EXECUTE,
                false,
                result.success() ? LogResult.SUCCESS : LogResult.REJECTED,
                commitState,
                result.errorCode(),
                result.success() ? "gm execute succeeded" : "gm execute rejected");
        return result;
    }

    private GmCommandResolution resolve(final String rawText) {
        Objects.requireNonNull(rawText, "rawText");
        return registry.resolve(GmCommandDsl.parse(rawText));
    }

    private AuditMetadata prepareMetadata(
            final GmCommandExecutionRequest request,
            final GmAuditPhase failurePhase,
            final GmBusinessCommitState failureCommitState) {
        try {
            GmAuditAttribution attribution = attributionFactory.create(request);
            List<String> parameterNames = request.definition().parameterNames();
            int parameterCount = request.arguments().size();
            return new AuditMetadata(
                    attribution,
                    request.context().traceId(),
                    request.commandKey(),
                    parameterNames,
                    parameterCount,
                    fingerprintPolicy.structureFingerprint(
                            request.commandKey(),
                            parameterNames,
                            attribution.targetType(),
                            parameterCount),
                    fingerprintPolicy.requestFingerprint(request));
        } catch (RuntimeException ex) {
            throw new GmAuditFailureException(failurePhase, failureCommitState, ex);
        }
    }

    private GmDryRunResult validateDryRunResult(
            final GmCommandExecutionRequest request,
            final GmDryRunResult result) {
        if (result == null || !request.commandKey().equals(result.commandKey())) {
            throw ZeroException.of(GmErrorCode.COMMAND_RESULT_INVALID);
        }
        return result;
    }

    private GmCommandExecutionResult validateExecutionResult(
            final GmCommandExecutionRequest request,
            final GmCommandExecutionResult result) {
        if (result == null || result.dryRun() || !request.commandKey().equals(result.commandKey())) {
            throw ZeroException.of(GmErrorCode.COMMAND_RESULT_INVALID);
        }
        return result;
    }

    private void appendFailureSafely(
            final AuditMetadata metadata,
            final GmAuditPhase phase,
            final boolean dryRun,
            final RuntimeException primaryFailure) {
        try {
            appendAudit(
                    metadata,
                    phase,
                    dryRun,
                    LogResult.FAILURE,
                    dryRun ? GmBusinessCommitState.NOT_APPLICABLE : GmBusinessCommitState.UNKNOWN,
                    errorCode(primaryFailure),
                    dryRun ? "gm dry-run failed" : "gm execute failed");
        } catch (RuntimeException auditFailure) {
            primaryFailure.addSuppressed(auditFailure);
        }
    }

    private void appendAudit(
            final AuditMetadata metadata,
            final GmAuditPhase phase,
            final boolean dryRun,
            final LogResult result,
            final GmBusinessCommitState businessCommitState,
            final ErrorCode errorCode,
            final String safeMessage) {
        try {
            auditHook.record(new GmAuditEvent(
                    Instant.now(clock),
                    phase,
                    metadata.attribution,
                    metadata.traceId,
                    metadata.commandKey,
                    metadata.parameterNames,
                    metadata.parameterCount,
                    dryRun,
                    result,
                    businessCommitState,
                    errorCode,
                    safeMessage,
                    metadata.structureFingerprint,
                    metadata.requestFingerprint));
        } catch (RuntimeException ex) {
            throw new GmAuditFailureException(phase, businessCommitState, ex);
        }
    }

    private ErrorCode errorCode(final RuntimeException ex) {
        if (ex instanceof ZeroException zeroException) {
            ErrorCode candidate = zeroException.errorCode();
            return SystemErrorCode.OK.code().equals(candidate.code())
                    ? GmErrorCode.COMMAND_RESULT_INVALID
                    : candidate;
        }
        return GmErrorCode.HANDLER_FAILED;
    }

    /**
     * 单次执行期间复用的安全审计元数据。
     *
     * <p>本类型只保存安全归因、显式 TraceId、结构元数据与安全指纹，不保存请求或参数值。</p>
     *
     * @author zn
     */
    private static final class AuditMetadata {

        /** 已完成构造前安全转换的归因。 */
        private final GmAuditAttribution attribution;

        /** 显式 TraceId。 */
        private final String traceId;

        /** 指令 key。 */
        private final String commandKey;

        /** 有序参数名白名单。 */
        private final List<String> parameterNames;

        /** 参数数量。 */
        private final int parameterCount;

        /** 不含值的结构指纹。 */
        private final String structureFingerprint;

        /** 可选请求 HMAC 关联指纹。 */
        private final String requestFingerprint;

        private AuditMetadata(
                final GmAuditAttribution attribution,
                final String traceId,
                final String commandKey,
                final List<String> parameterNames,
                final int parameterCount,
                final String structureFingerprint,
                final String requestFingerprint) {
            this.attribution = Objects.requireNonNull(attribution, "attribution");
            this.traceId = Objects.requireNonNull(traceId, "traceId");
            this.commandKey = Objects.requireNonNull(commandKey, "commandKey");
            this.parameterNames = List.copyOf(Objects.requireNonNull(parameterNames, "parameterNames"));
            this.parameterCount = parameterCount;
            this.structureFingerprint = Objects.requireNonNull(structureFingerprint, "structureFingerprint");
            this.requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        }
    }
}
