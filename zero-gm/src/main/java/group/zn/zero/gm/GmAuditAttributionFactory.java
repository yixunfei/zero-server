package group.zn.zero.gm;

import group.zn.zero.log.FixedLogIdentifierRedactor;
import group.zn.zero.log.HmacSha256LogIdentifierRedactor;
import group.zn.zero.log.LogIdentifierRedactor;
import java.util.Objects;

/**
 * 在 {@link GmAuditEvent} 构造前生成安全 GM 归因的工厂。
 *
 * <p>工厂同步读取执行请求中的原始值，只把固定脱敏值或带 keyId 的域分隔 HMAC 引用写入返回对象。
 * 不执行 DNS、外部 IO，不创建线程，也不保留上下文或请求引用。</p>
 *
 * @author zn
 */
public final class GmAuditAttributionFactory {

    /** 操作者 HMAC 域。 */
    private static final String OPERATOR_DOMAIN = "gm-operator";

    /** 来源地址 HMAC 域。 */
    private static final String SOURCE_DOMAIN = "gm-source";

    /** 审批单 HMAC 域。 */
    private static final String APPROVAL_DOMAIN = "gm-approval";

    /** 目标 HMAC 域。 */
    private static final String TARGET_DOMAIN = "gm-target";

    /** 安全引用最大长度，防止自定义输入形成无界审计字段。 */
    private static final int MAX_REFERENCE_LENGTH = 256;

    /** 标识安全转换器；只由本类安全工厂装配。 */
    private final LogIdentifierRedactor identifierRedactor;

    private GmAuditAttributionFactory(final LogIdentifierRedactor identifierRedactor) {
        this.identifierRedactor = Objects.requireNonNull(identifierRedactor, "identifierRedactor");
    }

    /**
     * 创建默认固定脱敏的归因工厂。
     *
     * @return 归因工厂；不可为空；不保留业务身份；线程安全。
     */
    public static GmAuditAttributionFactory redacted() {
        return new GmAuditAttributionFactory(FixedLogIdentifierRedactor.instance());
    }

    /**
     * 创建带部署密钥的 HMAC 归因工厂。
     *
     * <p>密钥由底层 redactor 防御复制；输出带 keyId，且四类字段使用独立域。
     * 本方法不修改调用方数组。</p>
     *
     * @param keyId 部署密钥标识；不可为空或空白，不应包含密钥本身。
     * @param key HMAC-SHA-256 部署密钥；不可为空或为空数组。
     * @return 归因工厂；不可为空；线程安全。
     * @throws group.zn.zero.core.error.ZeroException 当 keyId 或密钥不满足安全约束时抛出，
     *         并绑定 group.zn.zero.log.LogErrorCode.SENSITIVE_FIELD_REJECTED。
     */
    public static GmAuditAttributionFactory hmac(final String keyId, final byte[] key) {
        return new GmAuditAttributionFactory(new HmacSha256LogIdentifierRedactor(keyId, key));
    }

    /**
     * 从执行请求创建只含安全引用的归因。
     *
     * <p>原始 operator、来源地址、approvalId 和目标值仅在当前同步调用栈内读取，
     * 返回值不持有 {@link GmCommandContext}、{@link GmCommandExecutionRequest} 或任何原始值。</p>
     *
     * @param request GM 执行请求；不可为空。
     * @return 安全归因；不可为空；不可变且线程安全。
     * @throws NullPointerException 当请求为空时抛出。
     * @throws IllegalStateException 当 redactor 返回空值、原值、控制字符或超长引用时抛出。
     */
    public GmAuditAttribution create(final GmCommandExecutionRequest request) {
        GmCommandExecutionRequest checkedRequest = Objects.requireNonNull(request, "request");
        GmCommandContext context = checkedRequest.context();
        boolean approvalRequired = checkedRequest.definition().approvalRequired();
        String targetType = targetType(checkedRequest.definition().targetParameterName());
        String targetValue = checkedRequest.targetValue().orElse("");
        return GmAuditAttribution.safeReferences(
                redact(OPERATOR_DOMAIN, context.operator()),
                redact(SOURCE_DOMAIN, context.operatorIp()),
                approvalRequired,
                GmApprovalState.fromContext(approvalRequired, context.approvalState()),
                redactOptional(APPROVAL_DOMAIN, context.approvalId()),
                targetType,
                redactOptional(TARGET_DOMAIN, targetValue));
    }

    private String redactOptional(final String domain, final String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return "";
        }
        return redact(domain, rawValue);
    }

    private String redact(final String domain, final String rawValue) {
        String checkedRawValue = Objects.requireNonNull(rawValue, "rawValue");
        String reference = identifierRedactor.redact(domain, checkedRawValue);
        if (reference == null
                || reference.isBlank()
                || reference.length() > MAX_REFERENCE_LENGTH
                || reference.equals(checkedRawValue)
                || containsControlCharacter(reference)) {
            throw new IllegalStateException("gm audit redactor returned an unsafe reference");
        }
        return reference;
    }

    private String targetType(final String targetParameterName) {
        if (targetParameterName == null || targetParameterName.isBlank()) {
            return "none";
        }
        if (targetParameterName.endsWith("Id") && targetParameterName.length() > 2) {
            return targetParameterName.substring(0, targetParameterName.length() - 2);
        }
        return targetParameterName;
    }

    private boolean containsControlCharacter(final String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < 0x20 || (current >= 0x7F && current <= 0x9F)) {
                return true;
            }
        }
        return false;
    }
}
