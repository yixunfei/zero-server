package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.log.FixedLogIdentifierRedactor;
import group.zn.zero.log.LogResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * GM 指令执行器安全审计与四态提交语义测试。
 *
 * @author zn
 */
class GmCommandExecutorTest {

    /**
     * 验证 dry-run 不调用正式 handler，且事件只保存安全归因和参数名。
     */
    @Test
    void dryRunShouldUseSafeAttributionAndNotCallExecuteHandler() {
        AtomicBoolean executeCalled = new AtomicBoolean(false);
        InMemoryGmAuditHook auditHook = new InMemoryGmAuditHook();
        GmCommandExecutor executor = newExecutor(auditHook, new GmCommandHandler() {
            @Override
            public GmDryRunResult dryRun(final GmCommandExecutionRequest request) {
                return GmDryRunResult.accepted(
                        request.commandKey(),
                        "preview contains player-1",
                        Map.of("playerId", request.namedArguments().get("playerId")));
            }

            @Override
            public GmCommandExecutionResult execute(final GmCommandExecutionRequest request) {
                executeCalled.set(true);
                return GmCommandExecutionResult.success(request.commandKey(), "mail sent", Map.of());
            }
        });

        GmCommandExecutionResult result = executor.dryRun(context(), "/mail send player-1 item-2 10");

        assertTrue(result.dryRun());
        assertTrue(result.success());
        assertFalse(executeCalled.get());
        assertEquals(List.of(GmAuditPhase.BEFORE_DRY_RUN, GmAuditPhase.AFTER_DRY_RUN),
                auditHook.events().stream().map(GmAuditEvent::phase).toList());
        GmAuditEvent event = auditHook.events().getLast();
        assertEquals(GmBusinessCommitState.NOT_APPLICABLE, event.businessCommitState());
        assertEquals(LogResult.SUCCESS, event.result());
        assertNull(event.errorCode());
        assertEquals(List.of("playerId", "itemId", "count"), event.parameterNames());
        assertEquals(3, event.parameterCount());
        assertEquals("player", event.targetType());
        assertEquals(FixedLogIdentifierRedactor.REDACTED, event.targetRef());
        assertEquals(FixedLogIdentifierRedactor.REDACTED, event.attribution().operatorRef());
        assertEquals(FixedLogIdentifierRedactor.REDACTED, event.attribution().sourceAddressRef());
        assertEquals(FixedLogIdentifierRedactor.REDACTED, event.attribution().approvalRef());
        assertEquals(GmApprovalState.APPROVED, event.attribution().approvalState());
        assertFalse(event.toString().contains("player-1"));
        assertFalse(event.toString().contains("operator-1"));
        assertFalse(event.toString().contains("approval-1"));
    }

    /**
     * 验证正式成功结果从 NOT_COMMITTED 演进为 COMMITTED。
     */
    @Test
    void executeSuccessShouldExposeCommittedState() {
        InMemoryGmAuditHook auditHook = new InMemoryGmAuditHook();
        GmCommandExecutor executor = newExecutor(auditHook, successHandler());

        GmCommandExecutionResult result = executor.execute(context(), "/mail send player-1 item-2 10");

        assertTrue(result.success());
        assertEquals(List.of(GmBusinessCommitState.NOT_COMMITTED, GmBusinessCommitState.COMMITTED),
                auditHook.events().stream().map(GmAuditEvent::businessCommitState).toList());
        assertEquals(List.of(LogResult.STARTED, LogResult.SUCCESS),
                auditHook.events().stream().map(GmAuditEvent::result).toList());
        assertTrue(auditHook.events().stream().allMatch(event -> event.errorCode() == null));
    }

    /**
     * 验证正常无副作用拒绝携带真实 ErrorCode，并保持 NOT_COMMITTED。
     */
    @Test
    void executeRejectionShouldExposeRealErrorCodeWithoutCommit() {
        InMemoryGmAuditHook auditHook = new InMemoryGmAuditHook();
        GmCommandExecutor executor = newExecutor(auditHook, new GmCommandHandler() {
            @Override
            public GmDryRunResult dryRun(final GmCommandExecutionRequest request) {
                return GmDryRunResult.rejected(
                        request.commandKey(),
                        "business detail must stay outside audit",
                        Map.of(),
                        GmErrorCode.COMMAND_ARGUMENT_MISMATCH);
            }

            @Override
            public GmCommandExecutionResult execute(final GmCommandExecutionRequest request) {
                return GmCommandExecutionResult.rejected(
                        request.commandKey(),
                        "player-1 rejected for secret reason",
                        Map.of(),
                        GmErrorCode.COMMAND_ARGUMENT_MISMATCH);
            }
        });

        GmCommandExecutionResult result = executor.execute(context(), "/mail send player-1 item-2 10");

        assertFalse(result.success());
        assertEquals(GmErrorCode.COMMAND_ARGUMENT_MISMATCH, result.errorCode());
        GmAuditEvent event = auditHook.events().getLast();
        assertEquals(GmAuditPhase.AFTER_EXECUTE, event.phase());
        assertEquals(LogResult.REJECTED, event.result());
        assertEquals(GmBusinessCommitState.NOT_COMMITTED, event.businessCommitState());
        assertEquals(GmErrorCode.COMMAND_ARGUMENT_MISMATCH, event.errorCode());
        assertEquals("gm execute rejected", event.safeMessage());
        assertFalse(event.toString().contains("secret reason"));
    }

    /**
     * 验证 handler 的 ZeroException 保持主异常和领域 ErrorCode，异常 message 不进入事件。
     */
    @Test
    void zeroExceptionShouldRemainPrimaryAndKeepDomainErrorCode() {
        InMemoryGmAuditHook auditHook = new InMemoryGmAuditHook();
        ZeroException primary = new ZeroException(
                GmErrorCode.COMMAND_ARGUMENT_MISMATCH,
                "player-1 private handler detail");
        GmCommandExecutor executor = newExecutor(auditHook, throwingHandler(primary));

        ZeroException thrown = assertThrows(
                ZeroException.class,
                () -> executor.execute(context(), "/mail send player-1 item-2 10"));

        assertSame(primary, thrown);
        GmAuditEvent event = auditHook.events().getLast();
        assertEquals(GmAuditPhase.EXECUTE_FAILED, event.phase());
        assertEquals(GmBusinessCommitState.UNKNOWN, event.businessCommitState());
        assertEquals(GmErrorCode.COMMAND_ARGUMENT_MISMATCH, event.errorCode());
        assertEquals("gm execute failed", event.safeMessage());
        assertFalse(event.toString().contains("private handler detail"));
    }

    /**
     * 验证非 ZeroException handler 失败记录 HANDLER_FAILED，但原异常仍作为主异常抛出。
     */
    @Test
    void unknownHandlerFailureShouldUseFallbackCodeAndKeepPrimaryException() {
        InMemoryGmAuditHook auditHook = new InMemoryGmAuditHook();
        IllegalStateException primary = new IllegalStateException("raw target player-1");
        AtomicInteger handlerCalls = new AtomicInteger();
        GmCommandExecutor executor = newExecutor(auditHook, new GmCommandHandler() {
            @Override
            public GmDryRunResult dryRun(final GmCommandExecutionRequest request) {
                handlerCalls.incrementAndGet();
                throw primary;
            }

            @Override
            public GmCommandExecutionResult execute(final GmCommandExecutionRequest request) {
                handlerCalls.incrementAndGet();
                throw primary;
            }
        });

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(context(), "/mail send player-1 item-2 10"));

        assertSame(primary, thrown);
        GmAuditEvent event = auditHook.events().getLast();
        assertEquals(GmErrorCode.HANDLER_FAILED, event.errorCode());
        assertEquals(GmBusinessCommitState.UNKNOWN, event.businessCommitState());
        assertEquals(1, handlerCalls.get());
        assertFalse(event.toString().contains("raw target"));
    }

    /**
     * 验证 handler 错误地抛出 ZERO-OK 时审计改用 COMMAND_RESULT_INVALID，且不会自动重试。
     */
    @Test
    void zeroOkExceptionShouldNeverBecomeFailureAuditCode() {
        InMemoryGmAuditHook auditHook = new InMemoryGmAuditHook();
        AtomicInteger handlerCalls = new AtomicInteger();
        ZeroException primary = new ZeroException(SystemErrorCode.OK, "invalid success-code exception");
        GmCommandExecutor executor = newExecutor(auditHook, new GmCommandHandler() {
            @Override
            public GmDryRunResult dryRun(final GmCommandExecutionRequest request) {
                handlerCalls.incrementAndGet();
                throw primary;
            }

            @Override
            public GmCommandExecutionResult execute(final GmCommandExecutionRequest request) {
                handlerCalls.incrementAndGet();
                throw primary;
            }
        });

        ZeroException thrown = assertThrows(
                ZeroException.class,
                () -> executor.execute(context(), "/mail send player-1 item-2 10"));

        assertSame(primary, thrown);
        assertEquals(1, handlerCalls.get());
        assertEquals(GmErrorCode.COMMAND_RESULT_INVALID, auditHook.events().getLast().errorCode());
    }

    /**
     * 验证 handler 返回 null 时绑定 COMMAND_RESULT_INVALID 和 UNKNOWN，而不是伪造成功。
     */
    @Test
    void nullExecutionResultShouldBeInvalidAndCommitUnknown() {
        InMemoryGmAuditHook auditHook = new InMemoryGmAuditHook();
        GmCommandExecutor executor = newExecutor(auditHook, new GmCommandHandler() {
            @Override
            public GmDryRunResult dryRun(final GmCommandExecutionRequest request) {
                return null;
            }

            @Override
            public GmCommandExecutionResult execute(final GmCommandExecutionRequest request) {
                return null;
            }
        });

        ZeroException thrown = assertThrows(
                ZeroException.class,
                () -> executor.execute(context(), "/mail send player-1 item-2 10"));

        assertEquals(GmErrorCode.COMMAND_RESULT_INVALID, thrown.errorCode());
        GmAuditEvent event = auditHook.events().getLast();
        assertEquals(GmErrorCode.COMMAND_RESULT_INVALID, event.errorCode());
        assertEquals(GmBusinessCommitState.UNKNOWN, event.businessCommitState());
    }

    /**
     * 验证执行前审计失败阻断 handler，并明确暴露 NOT_COMMITTED。
     */
    @Test
    void beforeAuditFailureShouldPreventHandlerAndExposeNotCommitted() {
        AtomicBoolean handlerCalled = new AtomicBoolean(false);
        GmCommandExecutor executor = newExecutor(event -> {
            throw new IllegalStateException("audit unavailable");
        }, new GmCommandHandler() {
            @Override
            public GmDryRunResult dryRun(final GmCommandExecutionRequest request) {
                handlerCalled.set(true);
                return GmDryRunResult.accepted(request.commandKey(), "preview", Map.of());
            }

            @Override
            public GmCommandExecutionResult execute(final GmCommandExecutionRequest request) {
                handlerCalled.set(true);
                return GmCommandExecutionResult.success(request.commandKey(), "done", Map.of());
            }
        });

        GmAuditFailureException thrown = assertThrows(
                GmAuditFailureException.class,
                () -> executor.execute(context(), "/mail send player-1 item-2 10"));

        assertEquals(GmErrorCode.AUDIT_HOOK_FAILED, thrown.errorCode());
        assertEquals(GmAuditPhase.BEFORE_EXECUTE, thrown.phase());
        assertEquals(GmBusinessCommitState.NOT_COMMITTED, thrown.businessCommitState());
        assertFalse(handlerCalled.get());
    }

    /**
     * 验证成功后的审计失败不会伪造成执行失败，并暴露 COMMITTED 禁止错误重试。
     */
    @Test
    void afterSuccessAuditFailureShouldExposeCommitted() {
        AtomicInteger auditCalls = new AtomicInteger();
        AtomicInteger handlerCalls = new AtomicInteger();
        GmAuditHook hook = event -> {
            if (auditCalls.incrementAndGet() == 2) {
                throw new IllegalStateException("post audit unavailable");
            }
        };
        GmCommandExecutor executor = newExecutor(hook, new GmCommandHandler() {
            @Override
            public GmDryRunResult dryRun(final GmCommandExecutionRequest request) {
                return GmDryRunResult.accepted(request.commandKey(), "preview", Map.of());
            }

            @Override
            public GmCommandExecutionResult execute(final GmCommandExecutionRequest request) {
                handlerCalls.incrementAndGet();
                return GmCommandExecutionResult.success(request.commandKey(), "done", Map.of());
            }
        });

        GmAuditFailureException thrown = assertThrows(
                GmAuditFailureException.class,
                () -> executor.execute(context(), "/mail send player-1 item-2 10"));

        assertEquals(1, handlerCalls.get());
        assertEquals(2, auditCalls.get());
        assertEquals(GmAuditPhase.AFTER_EXECUTE, thrown.phase());
        assertEquals(GmBusinessCommitState.COMMITTED, thrown.businessCommitState());
    }

    /**
     * 验证 handler 主异常不被失败审计异常替换，审计异常仅作为 suppressed。
     */
    @Test
    void failedAuditShouldBeSuppressedOnHandlerPrimaryException() {
        IllegalStateException primary = new IllegalStateException("handler primary");
        GmAuditHook hook = event -> {
            if (event.phase() == GmAuditPhase.EXECUTE_FAILED) {
                throw new IllegalArgumentException("failure audit unavailable");
            }
        };
        GmCommandExecutor executor = newExecutor(hook, throwingHandler(primary));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(context(), "/mail send player-1 item-2 10"));

        assertSame(primary, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        GmAuditFailureException suppressed = assertInstanceOf(
                GmAuditFailureException.class,
                thrown.getSuppressed()[0]);
        assertEquals(GmAuditPhase.EXECUTE_FAILED, suppressed.phase());
        assertEquals(GmBusinessCommitState.UNKNOWN, suppressed.businessCommitState());
    }

    private GmCommandExecutor newExecutor(final GmAuditHook auditHook, final GmCommandHandler handler) {
        GmCommandRegistry registry = new GmCommandRegistry();
        registry.register(new GmCommandDefinition(
                List.of("mail", "send"),
                List.of("playerId", "itemId", "count"),
                "发送道具邮件",
                GmCommandRisk.MEDIUM,
                "playerId",
                true), handler);
        return new GmCommandExecutor(
                registry,
                auditHook,
                GmAuditAttributionFactory.redacted(),
                GmAuditFingerprintPolicy.structureOnly(),
                Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC));
    }

    private GmCommandContext context() {
        return new GmCommandContext(
                "operator-1",
                "127.0.0.1",
                "trace-gm-1",
                Set.of("administrator"),
                Set.of("gm.mail.send"),
                "approval-1",
                "approved",
                Map.of("token", "must-not-be-audited"));
    }

    private GmCommandHandler successHandler() {
        return new GmCommandHandler() {
            @Override
            public GmDryRunResult dryRun(final GmCommandExecutionRequest request) {
                return GmDryRunResult.accepted(request.commandKey(), "preview", Map.of());
            }

            @Override
            public GmCommandExecutionResult execute(final GmCommandExecutionRequest request) {
                return GmCommandExecutionResult.success(request.commandKey(), "mail sent", Map.of("mailId", "mail-1"));
            }
        };
    }

    private GmCommandHandler throwingHandler(final RuntimeException failure) {
        return new GmCommandHandler() {
            @Override
            public GmDryRunResult dryRun(final GmCommandExecutionRequest request) {
                throw failure;
            }

            @Override
            public GmCommandExecutionResult execute(final GmCommandExecutionRequest request) {
                throw failure;
            }
        };
    }
}
