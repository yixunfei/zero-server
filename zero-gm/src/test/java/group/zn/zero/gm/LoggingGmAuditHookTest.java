package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogPipeline;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * GM 审计事件统一日志管线接入测试。
 *
 * @author zn
 */
class LoggingGmAuditHookTest {

    /**
     * 验证 GM 审计通过 LogAppender 进入统一字段模型，且日志不含原始请求值。
     */
    @Test
    void loggingHookShouldAppendUnifiedSafeAuditRecords() {
        InMemoryLogSink sink = new InMemoryLogSink();
        LogPipeline pipeline = new LogPipeline(List.of(), sink);
        LoggingGmAuditHook hook = new LoggingGmAuditHook(
                pipeline,
                new GmAuditRecordFactory(new LogSource("gm-service", "local-1", "zero-gm")));
        GmCommandExecutor executor = newExecutor(hook);

        executor.execute(context(), "/mail send player-1 item-2 10");

        assertEquals(2, sink.records().size());
        ZeroLogRecord started = sink.records().getFirst();
        ZeroLogRecord succeeded = sink.records().getLast();
        assertEquals("1", started.schemaVersion());
        assertEquals(LogLevel.INFO, started.level());
        assertEquals(LogType.AUDIT, started.logType());
        assertEquals(LogResult.STARTED, started.result());
        assertNull(started.errorCode());
        assertEquals("gm-service", started.serviceName());
        assertEquals("local-1", started.instanceId());
        assertEquals("zero-gm", started.module());
        assertEquals("trace-gm-log", started.traceId());
        assertEquals(LogResult.SUCCESS, succeeded.result());
        assertEquals(GmBusinessCommitState.COMMITTED.name(),
                succeeded.fields().get("businessCommitState"));
        assertEquals("player", succeeded.fields().get("targetType"));
        assertEquals("[REDACTED]", succeeded.fields().get("operatorRef"));
        assertEquals("[REDACTED]", succeeded.fields().get("sourceAddressRef"));
        assertEquals("[REDACTED]", succeeded.fields().get("approvalRef"));
        assertEquals("[REDACTED]", succeeded.fields().get("targetRef"));
        assertFalse(containsRawValue(succeeded, "player-1"));
        assertFalse(containsRawValue(succeeded, "item-2"));
        assertFalse(containsRawValue(succeeded, "operator-1"));
        assertFalse(containsRawValue(succeeded, "approval-1"));
        assertFalse(containsRawValue(succeeded, "attribute-secret"));
    }

    private boolean containsRawValue(final ZeroLogRecord record, final String rawValue) {
        return record.message().contains(rawValue)
                || record.fields().keySet().stream().anyMatch(key -> key.contains(rawValue))
                || record.fields().values().stream().anyMatch(value -> value.contains(rawValue));
    }

    private GmCommandExecutor newExecutor(final GmAuditHook hook) {
        GmCommandRegistry registry = new GmCommandRegistry();
        registry.register(new GmCommandDefinition(
                List.of("mail", "send"),
                List.of("playerId", "itemId", "count"),
                "发送道具邮件",
                GmCommandRisk.MEDIUM,
                "playerId",
                true), new GmCommandHandler() {
                    @Override
                    public GmDryRunResult dryRun(final GmCommandExecutionRequest request) {
                        return GmDryRunResult.accepted(request.commandKey(), "preview", Map.of());
                    }

                    @Override
                    public GmCommandExecutionResult execute(final GmCommandExecutionRequest request) {
                        return GmCommandExecutionResult.success(
                                request.commandKey(),
                                "handler result containing player-1",
                                Map.of("mailId", "mail-1"));
                    }
                });
        return new GmCommandExecutor(
                registry,
                hook,
                GmAuditAttributionFactory.redacted(),
                GmAuditFingerprintPolicy.structureOnly(),
                Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC));
    }

    private GmCommandContext context() {
        return new GmCommandContext(
                "operator-1",
                "127.0.0.1",
                "trace-gm-log",
                Set.of("role-secret"),
                Set.of("permission-secret"),
                "approval-1",
                "approved",
                Map.of("token", "attribute-secret"));
    }
}
