package group.zn.zero.log;

import group.zn.zero.core.error.ZeroException;

/**
 * 标识脱敏输入和输出安全校验工具。
 *
 * @author zn
 */
final class LogRedactionSupport {

    /**
     * 工具类不允许实例化。
     */
    private LogRedactionSupport() {
    }

    /**
     * 校验并返回字段域。
     *
     * @param domain 字段域；不可为空。
     * @return 原字段域；不可为空。
     * @throws ZeroException 字段域空白、超长或包含控制字符时抛出。
     */
    static String requireDomain(final String domain) {
        if (domain == null
                || domain.isBlank()
                || !domain.equals(domain.trim())
                || domain.length() > ZeroLogRecord.MAX_IDENTIFIER_LENGTH
                || LogTextEscaper.containsControl(domain)) {
            throw rejected("redaction domain is invalid", null);
        }
        return domain;
    }

    /**
     * 校验并返回有界原始标识。
     *
     * @param identifier 原始标识；不可为空。
     * @return 原始标识；不可为空。
     * @throws ZeroException 标识为空或超过单字段预算时抛出。
     */
    static String requireIdentifier(final String identifier) {
        if (identifier == null || identifier.length() > ZeroLogRecord.MAX_FIELD_VALUE_LENGTH) {
            throw rejected("identifier is invalid", null);
        }
        return identifier;
    }

    /**
     * 校验并返回框架认可的脱敏结果。
     *
     * @param reference 脱敏结果；不可为空。
     * @return 原脱敏结果；不可为空。
     * @throws ZeroException 结果为空、超长、包含控制字符或不是固定/HMAC 安全引用时抛出。
     */
    static String requireReference(final String reference) {
        if (reference == null
                || reference.isEmpty()
                || reference.length() > ZeroLogRecord.MAX_FIELD_VALUE_LENGTH
                || LogTextEscaper.containsControl(reference)
                || !FixedLogIdentifierRedactor.REDACTED.equals(reference) && !isHmacReference(reference)) {
            throw rejected("redaction result is invalid", null);
        }
        return reference;
    }

    /**
     * 判断是否为带安全 keyId 的 HMAC-SHA-256 引用。
     *
     * @param reference 待检查引用；不可为空。
     * @return 前缀、keyId 和 64 位小写十六进制摘要均合法时返回 true。
     */
    private static boolean isHmacReference(final String reference) {
        String prefix = "hmac-sha256:";
        if (!reference.startsWith(prefix)) {
            return false;
        }
        int digestSeparator = reference.indexOf(':', prefix.length());
        if (digestSeparator <= prefix.length()
                || digestSeparator - prefix.length() > 64
                || reference.length() != digestSeparator + 65) {
            return false;
        }
        for (int index = prefix.length(); index < digestSeparator; index++) {
            char current = reference.charAt(index);
            boolean allowed = current >= 'a' && current <= 'z'
                    || current >= 'A' && current <= 'Z'
                    || current >= '0' && current <= '9'
                    || current == '.'
                    || current == '_'
                    || current == '-';
            if (!allowed) {
                return false;
            }
        }
        for (int index = digestSeparator + 1; index < reference.length(); index++) {
            char current = reference.charAt(index);
            if (!(current >= '0' && current <= '9' || current >= 'a' && current <= 'f')) {
                return false;
            }
        }
        return true;
    }

    /**
     * 创建敏感字段拒绝异常。
     *
     * @param message 安全诊断说明；不可为空。
     * @param cause 原始异常；可为空。
     * @return 统一异常；不可为空。
     */
    static ZeroException rejected(final String message, final Throwable cause) {
        return ZeroException.of(LogErrorCode.SENSITIVE_FIELD_REJECTED, message, cause);
    }
}
