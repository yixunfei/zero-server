package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * GM 构造前安全归因和指纹策略测试。
 *
 * @author zn
 */
class GmAuditSecurityTest {

    /** HMAC 测试 keyId。 */
    private static final String KEY_ID = "gm-audit-k1";

    /**
     * 验证相同原值在四个冻结字段域中生成不同的带 keyId HMAC 引用。
     */
    @Test
    void hmacAttributionShouldUseSeparatedDomainsAndNeverExposeRawValues() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x5A);
        InMemoryGmAuditHook auditHook = new InMemoryGmAuditHook();
        GmCommandExecutor executor = newExecutor(
                auditHook,
                GmAuditAttributionFactory.hmac(KEY_ID, key),
                GmAuditFingerprintPolicy.hmac(KEY_ID, key));
        Arrays.fill(key, (byte) 0);

        executor.execute(sameValueContext(), "/mail send same-sensitive item-2 10");

        GmAuditEvent event = auditHook.events().getLast();
        GmAuditAttribution attribution = event.attribution();
        List<String> references = List.of(
                attribution.operatorRef(),
                attribution.sourceAddressRef(),
                attribution.approvalRef(),
                attribution.targetRef());
        assertTrue(references.stream().allMatch(value -> value.startsWith("hmac-sha256:" + KEY_ID + ':')));
        assertEquals(4, references.stream().distinct().count());
        assertTrue(event.requestFingerprint().startsWith("hmac-sha256:" + KEY_ID + ':'));
        assertFalse(event.toString().contains("same-sensitive"));
        assertFalse(attribution.toString().contains("same-sensitive"));
        assertFalse(event.toString().contains(KEY_ID));
    }

    /**
     * 验证结构指纹只取决于命令 schema，请求值只有配置 HMAC 时才产生不同关联指纹。
     */
    @Test
    void structureFingerprintShouldIgnoreValuesWhileRequestHmacCorrelatesFullRequest() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x2C);
        InMemoryGmAuditHook auditHook = new InMemoryGmAuditHook();
        GmCommandExecutor executor = newExecutor(
                auditHook,
                GmAuditAttributionFactory.redacted(),
                GmAuditFingerprintPolicy.hmac(KEY_ID, key));

        executor.execute(context("operator-a", "192.0.2.1", "approval-a"), "/mail send player-a item-a 1");
        GmAuditEvent first = auditHook.events().getLast();
        executor.execute(context("operator-b", "192.0.2.2", "approval-b"), "/mail send player-b item-b 2");
        GmAuditEvent second = auditHook.events().getLast();

        assertEquals(first.structureFingerprint(), second.structureFingerprint());
        assertNotEquals(first.requestFingerprint(), second.requestFingerprint());
        assertFalse(first.requestFingerprint().contains("player-a"));
        assertFalse(second.requestFingerprint().contains("player-b"));
    }

    /**
     * 验证默认无密钥策略明确省略完整请求关联摘要。
     */
    @Test
    void defaultFingerprintPolicyShouldOmitRequestCorrelation() {
        InMemoryGmAuditHook auditHook = new InMemoryGmAuditHook();
        GmCommandExecutor executor = newExecutor(
                auditHook,
                GmAuditAttributionFactory.redacted(),
                GmAuditFingerprintPolicy.structureOnly());

        executor.execute(context("operator", "127.0.0.1", ""), "/mail send player item 1");

        GmAuditEvent event = auditHook.events().getLast();
        assertFalse(event.structureFingerprint().isBlank());
        assertTrue(event.requestFingerprint().isEmpty());
    }

    /**
     * 验证事件结构本身不含原上下文、执行请求或明确禁止的 raw 字段。
     */
    @Test
    void auditEventShapeShouldNotRetainRawContainersOrFields() {
        List<Field> fields = List.of(GmAuditEvent.class.getDeclaredFields());

        assertTrue(fields.stream().noneMatch(field -> field.getType() == GmCommandContext.class));
        assertTrue(fields.stream().noneMatch(field -> field.getType() == GmCommandExecutionRequest.class));
        Set<String> forbiddenNames = Set.of(
                "context",
                "request",
                "rawCommand",
                "rawText",
                "arguments",
                "target",
                "operator",
                "sourceAddress",
                "approvalId",
                "roles",
                "permissions",
                "attributes",
                "exceptionMessage");
        assertTrue(fields.stream().map(Field::getName).noneMatch(forbiddenNames::contains));
    }

    private GmCommandExecutor newExecutor(
            final GmAuditHook auditHook,
            final GmAuditAttributionFactory attributionFactory,
            final GmAuditFingerprintPolicy fingerprintPolicy) {
        GmCommandRegistry registry = new GmCommandRegistry();
        registry.register(new GmCommandDefinition(
                List.of("mail", "send"),
                List.of("playerId", "itemId", "count"),
                "发送道具邮件",
                GmCommandRisk.MEDIUM,
                "playerId",
                true), successHandler());
        return new GmCommandExecutor(
                registry,
                auditHook,
                attributionFactory,
                fingerprintPolicy,
                Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC));
    }

    private GmCommandContext sameValueContext() {
        return context("same-sensitive", "same-sensitive", "same-sensitive");
    }

    private GmCommandContext context(
            final String operator,
            final String sourceAddress,
            final String approvalId) {
        return new GmCommandContext(
                operator,
                sourceAddress,
                "trace-gm-security",
                Set.of("role-secret"),
                Set.of("permission-secret"),
                approvalId,
                "approved",
                Map.of("token", "attribute-secret"));
    }

    private GmCommandHandler successHandler() {
        return new GmCommandHandler() {
            @Override
            public GmDryRunResult dryRun(final GmCommandExecutionRequest request) {
                return GmDryRunResult.accepted(request.commandKey(), "preview", Map.of());
            }

            @Override
            public GmCommandExecutionResult execute(final GmCommandExecutionRequest request) {
                return GmCommandExecutionResult.success(request.commandKey(), "done", Map.of());
            }
        };
    }
}
