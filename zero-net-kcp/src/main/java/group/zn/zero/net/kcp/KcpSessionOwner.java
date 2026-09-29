package group.zn.zero.net.kcp;

import java.time.Instant;
import java.util.Objects;

/** 所有权快照；不可变、线程安全，不能代替数据面的本地单调租约检查。 @author zn */
public record KcpSessionOwner(int conv, String owner, long generation, Instant leaseExpiresAt, Phase phase) {
    /** 显式迁移状态；Redis 使用名称，不依赖 ordinal。 @author zn */
    public enum Phase { ACTIVE, FROZEN, PENDING, RELEASED }
    /** 参数无效时抛出 IllegalArgumentException，不修改存储。 */
    public KcpSessionOwner {
        requireIdentity(conv, owner, generation); Objects.requireNonNull(leaseExpiresAt); Objects.requireNonNull(phase);
    }
    /** @param conv 非零会话号。 @param owner 稳定节点名称。 @param generation 正代际；非法参数拒绝。 */
    public static void requireIdentity(final int conv, final String owner, final long generation) {
        if (conv == 0 || owner == null || !owner.matches("[A-Za-z0-9._-]{1,64}") || generation < 1) {
            throw new IllegalArgumentException("invalid KCP ownership identity");
        }
    }
}
