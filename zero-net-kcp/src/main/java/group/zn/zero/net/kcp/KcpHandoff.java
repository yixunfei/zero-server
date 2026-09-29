package group.zn.zero.net.kcp;

import java.time.Instant;
import java.util.Objects;

/**
 * 可信控制面的迁移结果；不含密钥、不构成凭证，目标仍验证登录主体和存储 CAS。
 * @param conv 保持不变的逻辑会话号。 @param generation 目标代际。 @param targetNode 目标节点 ID。
 * @param expiresAt 原票据过期时间，目标不可延长。不可变、线程安全。
 * @author zn
 */
public record KcpHandoff(int conv, long generation, String targetNode, Instant expiresAt) {
    /** 校验路由元数据，无 IO 副作用；非法参数拒绝。 */
    public KcpHandoff { KcpSessionOwner.requireIdentity(conv, targetNode, generation); Objects.requireNonNull(expiresAt); }
}
