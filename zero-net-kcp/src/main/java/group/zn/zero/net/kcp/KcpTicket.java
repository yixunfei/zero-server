package group.zn.zero.net.kcp;

import java.time.Instant;
import java.util.Objects;

/**
 * 经已认证 TCP 安全下发的会话票据；不可变、线程安全，密钥不可写入日志。
 * @author zn
 */
public final class KcpTicket {
    /** 32 位非零 conversation ID，不是身份凭证。 */
    private final int conv;
    /** 双向包认证密钥，必须只经安全控制通道传递。 */
    private final byte[] key;
    /** 绝对过期时间。 */
    private final Instant expiresAt;
    /** 会话所有权代际，迁移后严格递增。 */
    private final long generation;

    /**
     * 从可信控制通道还原票据；线程安全，复制密钥。
     * @param conv 非零会话号。
     * @param key 32 字节秘密。
     * @param expiresAt 绝对有效期。
     * @throws IllegalArgumentException 会话号或密钥长度非法。
     * @throws NullPointerException 参数为空。
     */
    public KcpTicket(final int conv, final byte[] key, final Instant expiresAt) {
        this(conv, key, expiresAt, 1L);
    }
    /**
     * 创建带所有权代际的票据。
     * @param conv 非零会话号。
     * @param key 32 字节秘密。
     * @param expiresAt 绝对有效期。
     * @param generation 正数代际。
     */
    public KcpTicket(final int conv, final byte[] key, final Instant expiresAt, final long generation) {
        if (conv == 0 || Objects.requireNonNull(key, "key").length != 32) {
            throw new IllegalArgumentException("nonzero conv and 32-byte key required");
        }
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        this.conv = conv;
        this.key = key.clone();
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.generation = generation;
    }
    /** @return 会话号；线程安全，无变更。 */
    public int conv() { return conv; }
    /** @return 密钥独立副本；调用者须保密，有序、非空，线程安全。 */
    public byte[] key() { return key.clone(); }
    /** @return 不可变绝对有效期；线程安全，无变更。 */
    public Instant expiresAt() { return expiresAt; }
    /** @return 所有权代际；线程安全，无变更。 */
    public long generation() { return generation; }
    /** @return 不含密钥的诊断文本；线程安全，无变更。 */
    @Override public String toString() {
        return "KcpTicket[conv=" + Integer.toUnsignedString(conv) + ", generation=" + generation
                + ", expiresAt=" + expiresAt + "]";
    }
}
