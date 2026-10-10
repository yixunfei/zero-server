package group.zn.zero.player;

import group.zn.zero.core.error.ZeroException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;

/**
 * 会话双向绑定与有界清理意图；实例短锁保护身份转换，不投递 Actor 或完成 future。
 * 解绑登记意图 → 锁外投递 → player lane 校验意图并清理 → 锁外通知；拒绝保留意图。
 * @author zn
 */
final class PlayerSessionBindings {
    /** 待清理 UID 的独立上限，满额在解绑前拒绝。 */
    private static final int DEFAULT_CLEANUP_CAPACITY = 4096;
    /** 账号到当前身份。 */
    private final Map<String, Binding> accounts = new HashMap<>();
    /** UID 到当前身份。 */
    private final Map<Long, Binding> uids = new HashMap<>();
    /** 当前会话的认证 token 摘要；解绑时删除，不保存原始凭据。 */
    private final Map<String, String> loginFingerprints = new HashMap<>();
    /** 每 UID 最多一个仍有效的清理意图，重新登录即失效。 */
    private final Map<Long, Cleanup> cleanups = new HashMap<>();
    /** 待清理容量。 */
    private final int capacity;

    PlayerSessionBindings() { this(DEFAULT_CLEANUP_CAPACITY); }

    PlayerSessionBindings(final int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("cleanup capacity must be positive");
        this.capacity = capacity;
    }

    /** 原子绑定并撤销同 UID 旧清理权限；不得在锁内调用用户代码。 */
    synchronized void login(final String account, final long uid, final String sessionId) {
        if (accounts.containsKey(account)) throw ZeroException.of(PlayerErrorCode.ACCOUNT_ALREADY_ONLINE);
        if (uids.containsKey(uid)) throw ZeroException.of(PlayerErrorCode.UID_ALREADY_ONLINE);
        Binding binding = new Binding(account, uid, sessionId);
        accounts.put(account, binding);
        uids.put(uid, binding);
        cleanups.remove(uid);
    }

    /** 仅在本次 UID 解析器重新认证成功后调用；同凭据、账号、UID 返回原身份。 */
    synchronized String loginAuthenticated(final String account, final long uid, final String sessionId,
            final String fingerprint) {
        Binding current = accounts.get(account);
        if (current != null && current.uid() == uid && fingerprint.equals(loginFingerprints.get(account))) {
            return current.sessionId();
        }
        login(account, uid, sessionId);
        loginFingerprints.put(account, fingerprint);
        return sessionId;
    }

    /** 返回不可变身份投影，缺失为空；只读且线程安全。 */
    synchronized Optional<Long> find(final String account) {
        Binding binding = accounts.get(account);
        return binding == null ? Optional.empty() : Optional.of(binding.uid());
    }

    /**
     * 精确解绑或认领原意图的重试。null 表示已结束/被替换/身份不符。
     * 在途只共享本次完成信号，不能重复投递；新意图满额时不修改绑定。
     */
    synchronized Attempt logout(final PlayerLogoutRequest request) {
        Binding expected = new Binding(request.accountId(), request.uid(), request.sessionId());
        Cleanup cleanup = cleanups.get(request.uid());
        if (cleanup == null || !cleanup.binding.equals(expected)) {
            if (!expected.equals(accounts.get(request.accountId()))) return null;
            if (cleanups.size() >= capacity) throw ZeroException.of(PlayerErrorCode.CLEANUP_CAPACITY_EXCEEDED);
            cleanup = new Cleanup(expected);
            cleanups.put(request.uid(), cleanup);
            accounts.remove(request.accountId());
            loginFingerprints.remove(request.accountId());
            uids.remove(request.uid());
        }
        if (cleanup.attempt != null) return new Attempt(cleanup, cleanup.attempt, false);
        cleanup.attempt = new CompletableFuture<>();
        return new Attempt(cleanup, cleanup.attempt, true);
    }

    /**
     * 仅 player lane 调用；删除权限与重新登录互斥。
     * removeProfile 只能是本地 players 表的删除动作，不能调用外部端口或完成 future。
     */
    synchronized void clean(final Attempt attempt, final LongConsumer removeProfile) {
        Cleanup cleanup = attempt.cleanup();
        long uid = cleanup.binding.uid();
        if (cleanups.get(uid) != cleanup || cleanup.attempt != attempt.result()) return;
        if (!uids.containsKey(uid)) removeProfile.accept(uid);
        cleanups.remove(uid, cleanup);
    }

    /** 失败尝试只释放重投资格，不丢掉待清理身份；调用方随后在锁外通知失败。 */
    synchronized void rejected(final Attempt attempt) {
        if (attempt.cleanup().attempt == attempt.result()) attempt.cleanup().attempt = null;
    }

    /** 精确身份；trace 不参与重试判定。 */
    private record Binding(String account, long uid, String sessionId) { }

    /** 内存清理意图，不保留历史完成收据或玩家 payload。 */
    private static final class Cleanup {
        /** 解绑的精确身份。 */
        private final Binding binding;
        /** 当前物理尝试；失败后清空以允许重试。 */
        private CompletableFuture<Boolean> attempt;

        private Cleanup(final Binding binding) { this.binding = binding; }
    }

    /** 一次准入结果；只有 submit=true 的调用方负责物理投递。 */
    record Attempt(Cleanup cleanup, CompletableFuture<Boolean> result, boolean submit) { }
}
