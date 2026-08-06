package group.zn.zero.gm;

import group.zn.zero.log.HmacSha256LogIdentifierRedactor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * GM 审计结构指纹与可选请求关联指纹策略。
 *
 * <p>结构指纹只包含 commandKey、参数名、目标类型和参数数量，可以使用无密钥 SHA-256。
 * 完整请求关联指纹只允许通过带部署密钥的 HMAC redactor 开启；没有密钥时返回空字符串，
 * 不生成低熵无盐摘要。</p>
 *
 * @author zn
 */
public final class GmAuditFingerprintPolicy {

    /** 结构指纹协议前缀。 */
    private static final String STRUCTURE_PREFIX = "zero-gm-structure-v1";

    /** 请求关联 HMAC 域。 */
    private static final String REQUEST_DOMAIN = "gm-request";

    /** 可选请求 HMAC redactor；为空表示不生成请求关联指纹。 */
    private final HmacSha256LogIdentifierRedactor requestRedactor;

    private GmAuditFingerprintPolicy(final HmacSha256LogIdentifierRedactor requestRedactor) {
        this.requestRedactor = requestRedactor;
    }

    /**
     * 创建只生成安全结构指纹的默认策略。
     *
     * @return 指纹策略；不可为空；不读取参数值；线程安全。
     */
    public static GmAuditFingerprintPolicy structureOnly() {
        return new GmAuditFingerprintPolicy(null);
    }

    /**
     * 创建同时生成域分隔 HMAC 请求关联指纹的策略。
     *
     * <p>密钥由底层 redactor 防御复制，本策略不会输出或在诊断字符串中显示密钥。</p>
     *
     * @param keyId 部署密钥标识；不可为空或空白。
     * @param key HMAC-SHA-256 部署密钥；不可为空或为空数组。
     * @return 指纹策略；不可为空；线程安全。
     * @throws group.zn.zero.core.error.ZeroException 当 keyId 或密钥不满足安全约束时抛出，
     *         并绑定 group.zn.zero.log.LogErrorCode.SENSITIVE_FIELD_REJECTED。
     */
    public static GmAuditFingerprintPolicy hmac(final String keyId, final byte[] key) {
        return new GmAuditFingerprintPolicy(new HmacSha256LogIdentifierRedactor(keyId, key));
    }

    /**
     * 生成不含参数值的确定性结构指纹。
     *
     * @param commandKey 指令 key；不可为空。
     * @param parameterNames 有序参数名；不可为空；不会被修改。
     * @param targetType 目标类型；不可为空。
     * @param parameterCount 参数数量；不可为负数。
     * @return {@code sha256:} 前缀的小写结构指纹；不可为空；线程安全。
     * @throws NullPointerException 当 key、参数名或目标类型为空时抛出。
     * @throws IllegalArgumentException 当参数数量为负数时抛出。
     * @throws IllegalStateException 当当前 JDK 不提供 SHA-256 时抛出。
     */
    public String structureFingerprint(
            final String commandKey,
            final List<String> parameterNames,
            final String targetType,
            final int parameterCount) {
        Objects.requireNonNull(commandKey, "commandKey");
        Objects.requireNonNull(parameterNames, "parameterNames");
        Objects.requireNonNull(targetType, "targetType");
        if (parameterCount < 0) {
            throw new IllegalArgumentException("parameterCount must not be negative");
        }
        MessageDigest digest = sha256();
        update(digest, STRUCTURE_PREFIX);
        update(digest, commandKey);
        update(digest, targetType);
        update(digest, Integer.toString(parameterCount));
        for (String parameterName : parameterNames) {
            update(digest, Objects.requireNonNull(parameterName, "parameterName"));
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 生成可选的完整请求关联 HMAC 指纹。
     *
     * <p>只有 {@link #hmac(String, byte[])} 创建的策略会短暂读取 rawText；默认策略返回空字符串。
     * 原始请求不会进入返回值或被本策略保存。</p>
     *
     * @param request GM 执行请求；不可为空。
     * @return 带算法和 keyId 的 HMAC 指纹；未配置密钥时为空字符串；线程安全。
     * @throws NullPointerException 当请求为空时抛出。
     */
    public String requestFingerprint(final GmCommandExecutionRequest request) {
        GmCommandExecutionRequest checkedRequest = Objects.requireNonNull(request, "request");
        if (requestRedactor == null) {
            return "";
        }
        return requestRedactor.redact(REQUEST_DOMAIN, checkedRequest.rawText());
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void update(final MessageDigest digest, final String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    /**
     * 返回不包含密钥或请求内容的诊断字符串。
     *
     * @return 有界诊断文本；不可为空；线程安全。
     */
    @Override
    public String toString() {
        return "GmAuditFingerprintPolicy{requestCorrelationEnabled=" + (requestRedactor != null) + '}';
    }
}
