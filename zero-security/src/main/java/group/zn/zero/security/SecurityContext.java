package group.zn.zero.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** 不可变认证身份及共享本地到期保护；线程安全，跨进程仅传签名快照。 @author zn */
public final class SecurityContext {
    /** 认证主体。 */
    private final String subject;
    /** 认证绝对时间。 */
    private final Instant authenticatedAt;
    /** 跨机绝对到期时间。 */
    private final Instant expiresAt;
    /** 传输类型。 */
    private final String transport;
    /** 直连地址。 */
    private final String peerAddress;
    /** 可信来源。 */
    private final String trustedSourceAddress;
    /** 追踪标识。 */
    private final String traceId;
    /** 关联标识。 */
    private final String correlationId;
    /** 不可变权限集合。 */
    private final Set<String> permissions;
    /** 不可变安全属性。 */
    private final Map<String, String> attributes;
    /** 同一认证代际共享的本地到期保护。 */
    private final SecurityContextExpiry expiry;

    /**
     * 以创建时剩余绝对寿命建立本地单调上限；线程安全，无外部副作用。
     * @param subject 主体。 @param authenticatedAt 认证时间。 @param expiresAt 到期时间。
     * @param transport 传输。 @param peerAddress 直连地址。 @param trustedSourceAddress 可信来源。
     * @param traceId 追踪。 @param correlationId 关联。 @param permissions 权限。 @param attributes 安全属性。
     * @throws IllegalArgumentException 字段非法。 @throws NullPointerException 必填字段为空。
     */
    public SecurityContext(String subject, Instant authenticatedAt, Instant expiresAt, String transport,
            String peerAddress, String trustedSourceAddress, String traceId, String correlationId,
            Set<String> permissions, Map<String, String> attributes) {
        this(subject, authenticatedAt, expiresAt, transport, peerAddress, trustedSourceAddress, traceId,
                correlationId, permissions, attributes, Clock.systemUTC(), System::nanoTime);
    }

    SecurityContext(String subject, Instant authenticatedAt, Instant expiresAt, String transport,
            String peerAddress, String trustedSourceAddress, String traceId, String correlationId,
            Set<String> permissions, Map<String, String> attributes, Clock clock, LongSupplier ticker) {
        this(subject, authenticatedAt, expiresAt, transport, peerAddress, trustedSourceAddress, traceId,
                correlationId, permissions, attributes, SecurityContextExpiry.create(expiresAt, clock, ticker));
    }

    private SecurityContext(String subject, Instant authenticatedAt, Instant expiresAt, String transport,
            String peerAddress, String trustedSourceAddress, String traceId, String correlationId,
            Set<String> permissions, Map<String, String> attributes, SecurityContextExpiry expiry) {
        SecurityValues.require(subject, "subject", 256);
        java.util.Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        java.util.Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(authenticatedAt)) throw new IllegalArgumentException("expiresAt must be after authenticatedAt");
        SecurityValues.require(transport, "transport", 32);
        SecurityValues.require(peerAddress, "peerAddress", 128);
        SecurityValues.require(trustedSourceAddress, "trustedSourceAddress", 128);
        SecurityValues.require(traceId, "traceId", 128);
        SecurityValues.require(correlationId, "correlationId", 128);
        this.subject = subject;
        this.authenticatedAt = authenticatedAt;
        this.expiresAt = expiresAt;
        this.transport = transport;
        this.peerAddress = peerAddress;
        this.trustedSourceAddress = trustedSourceAddress;
        this.traceId = traceId;
        this.correlationId = correlationId;
        this.permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        this.permissions.forEach(permission -> SecurityValues.require(permission, "permission", 256));
        this.attributes = SecurityValues.safeAttributes(attributes);
        this.expiry = Objects.requireNonNull(expiry, "expiry");
    }

    /**
     * 检查指定墙钟及共享单调上限；线程安全，到期不可恢复。
     * @param now 当前绝对时间。 @return 任一上限耗尽时为 true。 @throws NullPointerException now 为空。
     */
    public boolean expired(final Instant now) { return expiry.expired(expiresAt, Objects.requireNonNull(now, "now")); }

    /** @param permission 权限名。 @return 是否拥有权限；线程安全，无变更，空参数抛 NullPointerException。 */
    public boolean allows(final String permission) { return permissions.contains(Objects.requireNonNull(permission, "permission")); }

    /**
     * 派生可信本地传输上下文，保留同一单调到期上限；线程安全，不重置认证。
     * @param nextTransport 新传输名。 @param nextPeer 新直连地址。
     * @return 不可变身份及共享到期状态。 @throws IllegalArgumentException 传输或地址非法。
     */
    public SecurityContext withTransport(final String nextTransport, final String nextPeer) {
        return new SecurityContext(subject, authenticatedAt, expiresAt, nextTransport, nextPeer,
                trustedSourceAddress, traceId, correlationId, permissions, attributes, expiry);
    }

    /** @return 认证主体；线程安全，无副作用。 */
    public String subject() { return subject; }
    /** @return 认证时间；线程安全，无副作用。 */
    public Instant authenticatedAt() { return authenticatedAt; }
    /** @return 绝对到期时间；线程安全，无副作用。 */
    public Instant expiresAt() { return expiresAt; }
    /** @return 传输类型；线程安全，无副作用。 */
    public String transport() { return transport; }
    /** @return 直连地址；线程安全，无副作用。 */
    public String peerAddress() { return peerAddress; }
    /** @return 可信来源；线程安全，无副作用。 */
    public String trustedSourceAddress() { return trustedSourceAddress; }
    /** @return 追踪标识；线程安全，无副作用。 */
    public String traceId() { return traceId; }
    /** @return 关联标识；线程安全，无副作用。 */
    public String correlationId() { return correlationId; }
    /** @return 不可变、无序、可为空的权限集合；线程安全，无副作用。 */
    public Set<String> permissions() { return permissions; }
    /** @return 不可变、无序、可为空的属性集合；线程安全，无副作用。 */
    public Map<String, String> attributes() { return attributes; }
    /** @return 固定安全描述，不含身份、权限或属性。 */
    @Override public String toString() { return "SecurityContext[REDACTED]"; }
}
