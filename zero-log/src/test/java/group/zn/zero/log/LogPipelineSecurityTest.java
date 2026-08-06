package group.zn.zero.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 日志首次与终端安全门、敏感字段策略和标识脱敏测试。
 *
 * @author zn
 */
class LogPipelineSecurityTest {

    /**
     * 验证绝对禁止别名按完整路径段、大小写和分隔符识别。
     */
    @Test
    void forbiddenAliasesShouldBeRejectedByCompletePathSegment() {
        List<String> forbiddenPaths = List.of(
                "request.headers.Authorization", "cookie", "session_token.value", "db[password]",
                "passwd", "pwd", "secret", "apiKey", "accessKey", "secretKey", "credential",
                "credentials.privateKey", "request-commandText", "rawCommand", "api-key",
                "access_key", "private[key]", "raw-command");
        forbiddenPaths.forEach(path -> assertSensitiveRejected(record(Map.of(path, "secret"))));

        InMemoryLogSink sink = new InMemoryLogSink();
        new LogPipeline(List.of(), sink).append(record(Map.of(
                "tokenizer", "allowed because it is not a complete alias",
                "monkey", "also allowed")));
        assertEquals(1, sink.records().size());
    }

    /**
     * 验证 URI user-info、凭据连接串及敏感查询属性被有界检测并拒绝。
     */
    @Test
    void credentialConnectionsShouldBeRejectedWithoutUnboundedRegex() {
        assertSensitiveRejected(record(Map.of(
                "database.url", "jdbc:postgresql://user:password@localhost/game")));
        assertSensitiveRejected(record(Map.of(
                "mongo.uri", "mongodb://user:password@localhost/game")));
        assertSensitiveRejected(record(Map.of(
                "redis.uri", "redis://:password@localhost:6379")));
        assertSensitiveRejected(record(Map.of(
                "endpoint", "https://user:password@example.test/path")));
        assertSensitiveRejected(record(Map.of(
                "database.url", "jdbc:postgresql://localhost/game?password=secret")));
        assertSensitiveRejected(record(Map.of("note", "url?accessKey=secret")));
        assertSensitiveRejected(record(Map.of(), "connect mongodb+srv://user:secret@cluster/game"));
    }

    /**
     * 验证默认标识字段使用固定文本脱敏且不修改原记录。
     */
    @Test
    void identifierFieldsShouldBeRedactedWithoutMutatingInput() {
        ZeroLogRecord input = record(Map.of(
                "operator", "alice",
                "client.ip", "192.0.2.1",
                "accountId", "account-1",
                "player-id", "player-1",
                "target[id]", "target-1"));
        InMemoryLogSink sink = new InMemoryLogSink();

        new LogPipeline(List.of(), sink).append(input);

        ZeroLogRecord output = sink.records().getFirst();
        assertEquals("alice", input.fields().get("operator"));
        assertEquals("[REDACTED]", output.fields().get("operator"));
        assertEquals("[REDACTED]", output.fields().get("client.ip"));
        assertEquals("[REDACTED]", output.fields().get("accountId"));
        assertEquals("[REDACTED]", output.fields().get("player-id"));
        assertEquals("[REDACTED]", output.fields().get("target[id]"));
    }

    /**
     * 验证调用方策略只能收紧默认安全底线，不能放宽绝对禁止字段。
     */
    @Test
    void additionalPolicyShouldOnlyTightenBaseline() {
        SensitiveFieldPolicy attemptedRelaxation = (path, value) -> SensitiveFieldAction.ALLOW;
        LogPipeline pipeline = new LogPipeline(
                List.of(),
                new InMemoryLogSink(),
                attemptedRelaxation,
                FixedLogIdentifierRedactor.instance());

        ZeroException exception = assertThrows(ZeroException.class,
                () -> pipeline.append(record(Map.of("authorization", "secret"))));

        assertEquals(LogErrorCode.SENSITIVE_FIELD_REJECTED, exception.errorCode());

        InMemoryLogSink identifierSink = new InMemoryLogSink();
        new LogPipeline(
                List.of(),
                identifierSink,
                attemptedRelaxation,
                FixedLogIdentifierRedactor.instance())
                .append(record(Map.of("operator", "alice")));
        assertEquals("[REDACTED]", identifierSink.records().getFirst().fields().get("operator"));
    }

    /**
     * 验证附加策略可以把默认脱敏进一步收紧为整条记录拒绝。
     */
    @Test
    void additionalPolicyShouldRejectBaselineRedaction() {
        SensitiveFieldPolicy rejectOperator = (path, value) -> "operator".equals(path)
                ? SensitiveFieldAction.REJECT
                : SensitiveFieldAction.ALLOW;
        LogPipeline pipeline = new LogPipeline(
                List.of(),
                new InMemoryLogSink(),
                rejectOperator,
                FixedLogIdentifierRedactor.instance());

        ZeroException exception = assertThrows(ZeroException.class,
                () -> pipeline.append(record(Map.of("operator", "alice"))));

        assertEquals(LogErrorCode.SENSITIVE_FIELD_REJECTED, exception.errorCode());
    }

    /**
     * 验证安全管线只允许固定脱敏或带密钥 HMAC 两种不可扩展的 redactor 实现。
     */
    @Test
    void redactorContractShouldBeSealedToSafeImplementations() {
        assertTrue(LogIdentifierRedactor.class.isSealed());
        assertEquals(
                Set.of(FixedLogIdentifierRedactor.class, HmacSha256LogIdentifierRedactor.class),
                Set.of(LogIdentifierRedactor.class.getPermittedSubclasses()));

        ZeroException exception = assertThrows(
                ZeroException.class,
                () -> LogRedactionSupport.requireReference("passthrough-identifier"));
        assertEquals(LogErrorCode.SENSITIVE_FIELD_REJECTED, exception.errorCode());
    }

    /**
     * 验证调用方策略可以对框架未默认限制的字段进一步脱敏。
     */
    @Test
    void additionalPolicyShouldRedactExtraBusinessField() {
        SensitiveFieldPolicy businessPolicy = (path, value) -> "guildName".equals(path)
                ? SensitiveFieldAction.REDACT
                : SensitiveFieldAction.ALLOW;
        InMemoryLogSink sink = new InMemoryLogSink();
        LogPipeline pipeline = new LogPipeline(
                List.of(), sink, businessPolicy, FixedLogIdentifierRedactor.instance());

        pipeline.append(record(Map.of("guildName", "secret-guild")));

        assertEquals("[REDACTED]", sink.records().getFirst().fields().get("guildName"));
    }

    /**
     * 验证 processor 在首次门后重新注入绝对禁止字段仍被终端门阻断。
     */
    @Test
    void terminalGateShouldRejectSensitiveFieldInjectedByProcessor() {
        InMemoryLogSink sink = new InMemoryLogSink();
        LogPipeline pipeline = new LogPipeline(
                List.of(record -> record.withField("headers.Authorization", "secret")),
                sink);

        ZeroException exception = assertThrows(ZeroException.class,
                () -> pipeline.append(record(Map.of("safe", "value"))));

        assertEquals(LogErrorCode.SENSITIVE_FIELD_REJECTED, exception.errorCode());
        assertTrue(sink.records().isEmpty());
    }

    /**
     * 验证 processor 重新注入标识字段时终端门仍执行脱敏。
     */
    @Test
    void terminalGateShouldRedactIdentifierInjectedByProcessor() {
        InMemoryLogSink sink = new InMemoryLogSink();
        LogPipeline pipeline = new LogPipeline(
                List.of(record -> record.withField("playerId", "player-after-first-gate")),
                sink);

        pipeline.append(record(Map.of("safe", "value")));

        assertEquals("[REDACTED]", sink.records().getFirst().fields().get("playerId"));
    }

    /**
     * 验证消息和值中的控制字符被安全门转为字面量且原对象保持不变。
     */
    @Test
    void controlsShouldBeEscapedBeforeSink() {
        ZeroLogRecord input = record(
                Map.of("detail", "a\rb\nc\td\u0000e\u0085f"),
                "m\rn\np\tq\u0000r\u009fs");
        InMemoryLogSink sink = new InMemoryLogSink();

        new LogPipeline(List.of(), sink).append(input);

        ZeroLogRecord output = sink.records().getFirst();
        assertEquals("m\\rn\\np\\tq\\u0000r\\u009Fs", output.message());
        assertEquals("a\\rb\\nc\\td\\u0000e\\u0085f", output.fields().get("detail"));
        assertFalse(output.message().contains("\n"));
        assertEquals("m\rn\np\tq\u0000r\u009fs", input.message());
    }

    /**
     * 验证控制字符转义后的长度重新受总预算约束。
     */
    @Test
    void escapedContentShouldStillRespectBudgets() {
        ZeroLogRecord input = record(Map.of(), "\u0000".repeat(4096));

        ZeroException exception = assertThrows(ZeroException.class,
                () -> new LogPipeline(List.of(), new InMemoryLogSink()).append(input));

        assertEquals(LogErrorCode.INVALID_RECORD, exception.errorCode());
    }

    /**
     * 验证 HMAC 脱敏包含安全 keyId、结果稳定且按字段域隔离。
     */
    @Test
    void hmacRedactorShouldBeStableAndDomainSeparated() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x5A);
        HmacSha256LogIdentifierRedactor redactor =
                new HmacSha256LogIdentifierRedactor("deploy-2026-08", key);

        String operator = redactor.redact("operator", "alice");
        Arrays.fill(key, (byte) 0x00);
        String sameOperator = redactor.redact("operator", "alice");
        String player = redactor.redact("playerId", "alice");

        assertEquals(operator, sameOperator);
        assertNotEquals(operator, player);
        assertTrue(operator.startsWith("hmac-sha256:deploy-2026-08:"));
        assertFalse(operator.contains("alice"));
        assertEquals(64, operator.substring(operator.lastIndexOf(':') + 1).length());
    }

    /**
     * 验证伪造为当前 keyId HMAC 外形的原值仍会重新脱敏。
     */
    @Test
    void forgedHmacReferenceShouldNeverBypassRedaction() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x44);
        HmacSha256LogIdentifierRedactor redactor =
                new HmacSha256LogIdentifierRedactor("key-1", key);
        String forged = "hmac-sha256:key-1:" + "a".repeat(64);

        assertNotEquals(forged, redactor.redact("operator", forged));

        InMemoryLogSink inputSink = new InMemoryLogSink();
        new LogPipeline(List.of(), inputSink, SensitiveFieldPolicy.allowAll(), redactor)
                .append(record(Map.of("operator", forged)));
        assertNotEquals(forged, inputSink.records().getFirst().fields().get("operator"));

        InMemoryLogSink processorSink = new InMemoryLogSink();
        new LogPipeline(
                List.of(current -> current.withField("operator", forged)),
                processorSink,
                SensitiveFieldPolicy.allowAll(),
                redactor)
                .append(record(Map.of("safe", "value")));
        assertNotEquals(forged, processorSink.records().getFirst().fields().get("operator"));
    }

    /**
     * 验证管线只信任当前调用首次安全门生成的同位置引用，避免正常字段被二次 HMAC。
     */
    @Test
    void terminalGateShouldReuseOnlyCurrentPipelineProvenance() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x55);
        HmacSha256LogIdentifierRedactor redactor =
                new HmacSha256LogIdentifierRedactor("key-2", key);
        String expected = redactor.redact("operator", "alice");
        InMemoryLogSink sink = new InMemoryLogSink();

        new LogPipeline(
                List.of(current -> current),
                sink,
                SensitiveFieldPolicy.allowAll(),
                redactor)
                .append(record(Map.of("operator", "alice")));

        assertEquals(expected, sink.records().getFirst().fields().get("operator"));

        InMemoryLogSink movedSink = new InMemoryLogSink();
        new LogPipeline(
                List.of(current -> current.withField(
                        "playerId", current.fields().get("operator"))),
                movedSink,
                SensitiveFieldPolicy.allowAll(),
                redactor)
                .append(record(Map.of("operator", "alice")));

        assertEquals(expected, movedSink.records().getFirst().fields().get("operator"));
        assertEquals(
                redactor.redact("playerId", expected),
                movedSink.records().getFirst().fields().get("playerId"));
    }

    /**
     * 验证附加策略异常的 message、伪造堆栈、cause 和 suppressed 图均不会跨越安全边界。
     */
    @Test
    void policyFailureShouldSanitizeUntrustedCause() {
        String secret = "alice-secret";
        StackTraceElement forgedElement = new StackTraceElement(
                "leaked." + secret,
                "policyValue_" + secret,
                "Leaked-" + secret + ".java",
                73);
        IllegalStateException nestedCause = new IllegalStateException("nested=" + secret);
        nestedCause.setStackTrace(new StackTraceElement[] {forgedElement});
        IllegalArgumentException suppressed = new IllegalArgumentException("suppressed=" + secret);
        suppressed.setStackTrace(new StackTraceElement[] {forgedElement});
        IllegalArgumentException untrusted = new IllegalArgumentException("leaked=" + secret, nestedCause);
        untrusted.setStackTrace(new StackTraceElement[] {forgedElement});
        untrusted.addSuppressed(suppressed);
        SensitiveFieldPolicy leakingPolicy = (path, value) -> {
            throw untrusted;
        };
        LogPipeline pipeline = new LogPipeline(
                List.of(),
                new InMemoryLogSink(),
                leakingPolicy,
                FixedLogIdentifierRedactor.instance());

        ZeroException exception = assertThrows(
                ZeroException.class,
                () -> pipeline.append(record(Map.of("operator", secret))));

        assertEquals(LogErrorCode.SENSITIVE_FIELD_REJECTED, exception.errorCode());
        assertNotSame(untrusted, exception.getCause());
        assertNull(exception.getCause().getCause());
        assertEquals(0, exception.getCause().getSuppressed().length);
        assertEquals(0, exception.getSuppressed().length);
        String safeGraph = throwableText(exception);
        assertFalse(safeGraph.contains(secret));
        assertFalse(safeGraph.contains(forgedElement.getClassName()));
        assertFalse(safeGraph.contains(forgedElement.getMethodName()));
        assertFalse(safeGraph.contains(forgedElement.getFileName()));
        assertTrue(safeGraph.contains(IllegalArgumentException.class.getName()));
    }

    /**
     * 验证 HMAC redactor 可由标准管线使用且不会修改源字段。
     */
    @Test
    void pipelineShouldUseInjectedHmacRedactor() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x33);
        InMemoryLogSink sink = new InMemoryLogSink();
        LogPipeline pipeline = new LogPipeline(
                List.of(),
                sink,
                SensitiveFieldPolicy.allowAll(),
                new HmacSha256LogIdentifierRedactor("key-1", key));
        ZeroLogRecord input = record(Map.of("operator", "alice"));

        pipeline.append(input);

        String reference = sink.records().getFirst().fields().get("operator");
        assertTrue(reference.startsWith("hmac-sha256:key-1:"));
        assertEquals("alice", input.fields().get("operator"));
        assertSame(LogResult.SUCCESS, sink.records().getFirst().result());
    }

    /**
     * 断言日志经默认管线被敏感字段错误码拒绝。
     *
     * @param record 日志记录；不可为空。
     */
    private void assertSensitiveRejected(final ZeroLogRecord record) {
        ZeroException exception = assertThrows(ZeroException.class,
                () -> new LogPipeline(List.of(), new InMemoryLogSink()).append(record));
        assertEquals(LogErrorCode.SENSITIVE_FIELD_REJECTED, exception.errorCode());
    }

    /**
     * 创建安全成功日志。
     *
     * @param fields 字段；不可为空。
     * @return 日志记录；不可为空；线程安全。
     */
    private ZeroLogRecord record(final Map<String, String> fields) {
        return record(fields, "message");
    }

    /**
     * 创建指定消息的成功日志。
     *
     * @param fields 字段；不可为空。
     * @param message 消息；不可为空。
     * @return 日志记录；不可为空；线程安全。
     */
    private ZeroLogRecord record(final Map<String, String> fields, final String message) {
        return ZeroLogRecord.create(
                Instant.parse("2026-08-04T00:00:00Z"),
                LogLevel.INFO,
                LogType.BUSINESS,
                new LogSource("game-service", "local-1", "zero-log"),
                new LogOperation("security.test", LogResult.SUCCESS, null),
                "trace-security",
                message,
                fields);
    }

    /**
     * 递归汇总异常图的类型、消息、堆栈、cause 和 suppressed，供泄漏断言使用。
     *
     * @param throwable 根异常；不可为空。
     * @return 有序诊断文本；不可为空。
     */
    private String throwableText(final Throwable throwable) {
        StringBuilder text = new StringBuilder();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        appendThrowableText(throwable, text, visited);
        return text.toString();
    }

    /**
     * 以身份去重方式递归追加单个异常节点，防止异常图环导致测试失控。
     *
     * @param throwable 当前异常；可为空。
     * @param text 可变诊断文本；不可为空。
     * @param visited 已访问异常身份集合；不可为空、无序且非线程安全。
     */
    private void appendThrowableText(
            final Throwable throwable,
            final StringBuilder text,
            final Set<Throwable> visited) {
        if (throwable == null || !visited.add(throwable)) {
            return;
        }
        text.append(throwable.getClass().getName()).append(':').append(throwable.getMessage()).append('\n');
        for (StackTraceElement element : throwable.getStackTrace()) {
            text.append(element).append('\n');
        }
        for (Throwable suppressed : throwable.getSuppressed()) {
            appendThrowableText(suppressed, text, visited);
        }
        appendThrowableText(throwable.getCause(), text, visited);
    }
}
