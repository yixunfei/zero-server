package group.zn.zero.log;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 使用部署密钥和字段域分隔生成 HMAC-SHA-256 日志安全引用的脱敏器。
 *
 * <p>每次调用使用独立 JCA {@link Mac}，不创建 ThreadLocal、不共享非线程安全状态，也不保留调用方
 * key 数组引用。该实现适合需要跨记录安全关联的低频字段；默认热路径应优先使用固定脱敏器。
 *
 * @author zn
 */
public final class HmacSha256LogIdentifierRedactor implements LogIdentifierRedactor {

    /**
     * JCA 算法名。
     */
    private static final String ALGORITHM = "HmacSHA256";

    /**
     * HMAC 输入域版本前缀。
     */
    private static final byte[] INPUT_PREFIX =
            "zero-log-identifier-v1".getBytes(StandardCharsets.US_ASCII);

    /**
     * 最小部署密钥字节数。
     */
    private static final int MIN_KEY_BYTES = 32;

    /**
     * 十六进制编码器。
     */
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    /**
     * 输出前缀，包含安全 keyId。
     */
    private final String outputPrefix;

    /**
     * 不暴露编码内容的密钥规格。
     */
    private final SecretKeySpec secretKey;

    /**
     * 创建 HMAC 脱敏器。
     *
     * <p>构造函数会复制密钥材料；调用方仍应在构造后按自身生命周期擦除原数组。实例不可变且线程安全。
     *
     * @param keyId 非敏感部署密钥版本标识；长度 1～64，仅允许 ASCII 字母、数字、点、下划线和连字符。
     * @param key 至少 32 字节的部署密钥；不可为空；构造后不会保留原数组引用。
     * @throws group.zn.zero.core.error.ZeroException keyId 或密钥非法时抛出，并绑定
     *         {@link LogErrorCode#SENSITIVE_FIELD_REJECTED}。
     */
    public HmacSha256LogIdentifierRedactor(final String keyId, final byte[] key) {
        String checkedKeyId = requireKeyId(keyId);
        if (key == null || key.length < MIN_KEY_BYTES) {
            throw LogRedactionSupport.rejected("HMAC key is invalid", null);
        }
        this.outputPrefix = "hmac-sha256:" + checkedKeyId + ':';
        this.secretKey = new SecretKeySpec(key.clone(), ALGORITHM);
    }

    /**
     * 生成带字段域分隔的 HMAC 安全引用。
     *
     * <pre>
     * HMAC(key, "zero-log-identifier-v1" + NUL + domain + NUL + identifier)
     * </pre>
     *
     * @param domain 稳定字段域；不可为空或空白，最长 128 字符且无控制字符。
     * @param identifier 原始标识；不可为空。
     * @return `hmac-sha256:keyId:lowercaseHex`；不可为空；线程安全。
     * @throws group.zn.zero.core.error.ZeroException 输入或 JCA 执行失败时抛出，并绑定
     *         {@link LogErrorCode#SENSITIVE_FIELD_REJECTED}；异常不暴露密钥或标识原值。
     */
    @Override
    public String redact(final String domain, final String identifier) {
        String checkedDomain = LogRedactionSupport.requireDomain(domain);
        String checkedIdentifier = LogRedactionSupport.requireIdentifier(identifier);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secretKey);
            mac.update(INPUT_PREFIX);
            mac.update((byte) 0);
            mac.update(checkedDomain.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            byte[] digest = mac.doFinal(checkedIdentifier.getBytes(StandardCharsets.UTF_8));
            return outputPrefix + HEX_FORMAT.formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw LogRedactionSupport.rejected("HMAC redaction failed", exception);
        }
    }

    /**
     * 校验 keyId。
     *
     * @param keyId keyId；不可为空。
     * @return 原 keyId；不可为空。
     * @throws group.zn.zero.core.error.ZeroException keyId 非法时抛出。
     */
    private static String requireKeyId(final String keyId) {
        if (keyId == null || keyId.isEmpty() || keyId.length() > 64) {
            throw LogRedactionSupport.rejected("HMAC keyId is invalid", null);
        }
        for (int index = 0; index < keyId.length(); index++) {
            char current = keyId.charAt(index);
            boolean allowed = current >= 'a' && current <= 'z'
                    || current >= 'A' && current <= 'Z'
                    || current >= '0' && current <= '9'
                    || current == '.'
                    || current == '_'
                    || current == '-';
            if (!allowed) {
                throw LogRedactionSupport.rejected("HMAC keyId is invalid", null);
            }
        }
        return keyId;
    }
}
