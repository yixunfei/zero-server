package group.zn.zero.gm;

import group.zn.zero.core.error.ZeroException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * REST/RPC/CLI 共用授权入口：解析 → 授权 → claim → 审计及执行 → 终态记账。
 * 不持锁调用业务，线程安全取决于依赖；在途操作不得按终态 TTL 回收。
 * @author zn
 */
public final class GmOperationEndpoint {
    /** 默认终态保留期，从业务完成时起算。 */
    private static final Duration RETENTION = Duration.ofMinutes(10);
    /** 业务执行及安全审计。 */
    private final GmCommandExecutor executor;
    /** 可信授权边界。 */
    private final GmOperationAuthorizer authorizer;
    /** 可空幂等后端；生命周期由装配方持有。 */
    private final GmIdempotencyStore idempotencyStore;
    /** 终态时间源，便于确定性回归。 */
    private final Clock clock;
    /** 与幂等存储作用域一致的 HMAC 策略。 */
    private final GmOperationFingerprint fingerprints;

    /** @param executor 执行器。 @param authorizer 授权器。构造无副作用，不启用幂等。 */
    public GmOperationEndpoint(GmCommandExecutor executor, GmOperationAuthorizer authorizer) {
        this(executor, authorizer, null);
    }

    /**
     * 创建同 JVM 本地端点；持久化/跨进程幂等必须使用显式部署密钥的构造器。
     * @param executor 执行器。 @param authorizer 授权器。 @param idempotencyStore 可空本地后端。
     */
    public GmOperationEndpoint(GmCommandExecutor executor, GmOperationAuthorizer authorizer, GmIdempotencyStore idempotencyStore) {
        this(executor, authorizer, idempotencyStore, Clock.systemUTC(), GmOperationFingerprint.processLocal());
    }

    /**
     * 创建显式时间及部署指纹策略端点；无 IO，无请求状态保留。
     * @param executor 执行器。 @param authorizer 授权器。 @param idempotencyStore 可空幂等后端。
     * @param clock 终态时间源。 @param fingerprints 与后端共享作用域/密钥版本的策略。
     * @throws NullPointerException 除可空后端外有空依赖；线程安全取决于注入端口。
     */
    public GmOperationEndpoint(GmCommandExecutor executor, GmOperationAuthorizer authorizer,
            GmIdempotencyStore idempotencyStore, Clock clock, GmOperationFingerprint fingerprints) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.idempotencyStore = idempotencyStore;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.fingerprints = Objects.requireNonNull(fingerprints, "fingerprints");
    }

    /**
     * 执行或预演，重放仍重新授权；稳定响应保留提交事实与基础设施故障标记。
     * @param request 操作请求，不可为空。 @return 安全不可变响应，线程安全取决于依赖。
     * @throws NullPointerException 请求为空。 @throws Error 致命错误保持原实例，审计/记账异常仅 suppressed。
     */
    public GmOperationResponse handle(final GmOperationRequest request) {
        Objects.requireNonNull(request, "request");
        GmCommandResolution resolution = null;
        try {
            resolution = executor.resolve(request.rawCommand());
            authorizer.authorize(request.context(), resolution, request.target(), request.approvalToken(), request.secondReviewer());
        } catch (RuntimeException failure) {
            return auditBoundary(request, resolution, GmAuditPhase.REJECTED, rejected(request, failure));
        } catch (Error failure) {
            auditFatal(request, resolution, failure);
            throw failure;
        }
        boolean idempotent = idempotencyStore != null && !request.idempotencyKey().isBlank() && !request.dryRun();
        String fingerprint = "";
        if (idempotent) {
            GmIdempotencyStore.Claim claim;
            try {
                fingerprint = fingerprints.fingerprint(request);
                claim = Objects.requireNonNull(idempotencyStore.claim(request.idempotencyKey(), fingerprint,
                        clock.instant(), RETENTION), "claim");
            } catch (RuntimeException failure) {
                var response = rejected(request, GmErrorCode.IDEMPOTENCY_STORE_FAILED).withIdempotencyFailure();
                return auditBoundary(request, resolution, GmAuditPhase.REJECTED, response);
            } catch (Error failure) {
                auditFatal(request, resolution, failure);
                throw failure;
            }
            if (claim.state() == GmIdempotencyStore.State.COMPLETED) {
                return auditBoundary(request, resolution, GmAuditPhase.REPLAYED, claim.response().withTrace(request.context().traceId()));
            }
            GmErrorCode rejection = rejection(claim.state());
            if (rejection != null) return auditBoundary(request, resolution, GmAuditPhase.REJECTED, rejected(request, rejection));
        }
        return execute(request, resolution, idempotent, fingerprint);
    }

    private GmOperationResponse execute(GmOperationRequest request, GmCommandResolution resolution,
            boolean idempotent, String fingerprint) {
        GmOperationResponse response;
        boolean completed = false;
        try {
            GmCommandExecutionResult result = request.dryRun()
                    ? executor.dryRun(request.context(), resolution) : executor.execute(request.context(), resolution);
            response = GmOperationResponse.success(result, request.context().traceId());
            completed = true;
        } catch (RuntimeException failure) {
            GmBusinessCommitState state = failure instanceof GmAuditFailureException audit ? audit.businessCommitState()
                    : request.dryRun() ? GmBusinessCommitState.NOT_APPLICABLE : GmBusinessCommitState.UNKNOWN;
            response = GmOperationResponse.failed(code(failure), request.context().traceId(), state, auditFailed(failure));
        } catch (Error failure) {
            if (idempotent) failSafely(request, fingerprint, failure);
            throw failure;
        }
        if (!idempotent) return response;
        try {
            if (completed) {
                idempotencyStore.complete(request.idempotencyKey(), fingerprint, response, clock.instant().plus(RETENTION));
            } else {
                idempotencyStore.fail(request.idempotencyKey(), fingerprint, clock.instant().plus(RETENTION));
            }
            return response;
        } catch (RuntimeException failure) {
            return auditBoundary(request, resolution, GmAuditPhase.BOOKKEEPING_FAILED, response.withIdempotencyFailure());
        }
    }

    private void failSafely(GmOperationRequest request, String fingerprint, Error primary) {
        try {
            idempotencyStore.fail(request.idempotencyKey(), fingerprint, clock.instant().plus(RETENTION));
        } catch (RuntimeException | Error failure) {
            if (failure != primary) primary.addSuppressed(failure);
        }
    }

    private GmOperationResponse auditBoundary(GmOperationRequest request, GmCommandResolution resolution,
            GmAuditPhase phase, GmOperationResponse response) {
        try {
            executor.auditBoundary(request, resolution, phase, response);
            return response;
        } catch (RuntimeException failure) {
            return response.withAuditFailure();
        }
    }

    private void auditFatal(GmOperationRequest request, GmCommandResolution resolution, Error primary) {
        try {
            executor.auditBoundary(request, resolution, GmAuditPhase.REJECTED, rejected(request, GmErrorCode.HANDLER_FAILED));
        } catch (RuntimeException | Error failure) {
            if (failure != primary) primary.addSuppressed(failure);
        }
    }

    private static GmOperationResponse rejected(GmOperationRequest request, RuntimeException failure) {
        var response = rejected(request, code(failure));
        return auditFailed(failure) ? response.withAuditFailure() : response;
    }

    private static GmOperationResponse rejected(GmOperationRequest request, GmErrorCode code) {
        GmBusinessCommitState state = code == GmErrorCode.IDEMPOTENCY_IN_PROGRESS || code == GmErrorCode.IDEMPOTENCY_FAILED
                ? GmBusinessCommitState.UNKNOWN : GmBusinessCommitState.NOT_COMMITTED;
        return GmOperationResponse.failed(code, request.context().traceId(), request.dryRun()
                ? GmBusinessCommitState.NOT_APPLICABLE : state, false);
    }

    private static GmErrorCode code(RuntimeException failure) {
        return failure instanceof ZeroException zero && zero.errorCode() instanceof GmErrorCode gm ? gm
                : GmErrorCode.HANDLER_FAILED;
    }

    private static boolean auditFailed(Throwable failure) {
        return failure instanceof GmAuditFailureException || java.util.Arrays.stream(failure.getSuppressed())
                .anyMatch(GmAuditFailureException.class::isInstance);
    }

    private static GmErrorCode rejection(GmIdempotencyStore.State state) {
        return switch (state) {
            case CLAIMED -> null;
            case CAPACITY_REJECTED -> GmErrorCode.IDEMPOTENCY_CAPACITY_EXCEEDED;
            case CONFLICT -> GmErrorCode.IDEMPOTENCY_CONFLICT;
            case IN_PROGRESS -> GmErrorCode.IDEMPOTENCY_IN_PROGRESS;
            case FAILED -> GmErrorCode.IDEMPOTENCY_FAILED;
            case COMPLETED -> throw new IllegalStateException("completed claim already handled");
        };
    }
}
