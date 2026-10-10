package group.zn.zero.gm;

import group.zn.zero.log.HmacSha256LogIdentifierRedactor;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.Objects;

/** 有界、长度前缀、域隔离的操作 HMAC；不保留请求，线程安全。 @author zn */
public final class GmOperationFingerprint {
    /** 类生命周期内共享的密码学安全随机源。 */
    private static final SecureRandom RANDOM = new SecureRandom();
    /** JVM 生命周期内共享的本地策略；持久化或多进程部署必须显式配置相同密钥。 */
    private static final GmOperationFingerprint LOCAL = localPolicy();
    /** 防御复制密钥的现有 HMAC 实现。 */
    private final HmacSha256LogIdentifierRedactor redactor;

    private GmOperationFingerprint(String keyId, byte[] key) {
        redactor = new HmacSha256LogIdentifierRedactor(keyId, key);
    }

    /**
     * 创建部署级稳定指纹策略；不执行 IO、不保留传入数组，线程安全。
     * @param keyId 非敏感密钥版本。 @param key 至少 32 字节部署密钥。
     * @return 不可变策略。 @throws group.zn.zero.core.error.ZeroException 密钥参数无效。
     */
    public static GmOperationFingerprint hmac(String keyId, byte[] key) {
        return new GmOperationFingerprint(keyId, key);
    }

    /** @return 仅在当前 JVM 内稳定的策略；无数据变更，线程安全。 */
    public static GmOperationFingerprint processLocal() { return LOCAL; }

    /**
     * 生成包括操作者、目标、命令、审批令牌和复核人的完整指纹；不含可变 trace。
     * @param request 有界操作请求。 @return 带算法及 keyId 的指纹，不包含原值。
     * @throws NullPointerException 请求为空；同步执行，无外部副作用，线程安全。
     */
    public String fingerprint(GmOperationRequest request) {
        Objects.requireNonNull(request, "request");
        MessageDigest canonical = sha256();
        for (String value : java.util.List.of(request.context().operator(), request.target(), request.rawCommand(),
                Boolean.toString(request.dryRun()), request.approvalToken(), request.secondReviewer())) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            canonical.update((byte) (bytes.length >>> 24));
            canonical.update((byte) (bytes.length >>> 16));
            canonical.update((byte) (bytes.length >>> 8));
            canonical.update((byte) bytes.length);
            canonical.update(bytes);
        }
        // 中间摘要仅在栈内传给 HMAC，不输出、不存储；避免日志 redactor 单字段预算限制完整请求。
        return redactor.redact("gm-operation-v2", HexFormat.of().formatHex(canonical.digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static GmOperationFingerprint localPolicy() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        try {
            return hmac("process-local", key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
}
