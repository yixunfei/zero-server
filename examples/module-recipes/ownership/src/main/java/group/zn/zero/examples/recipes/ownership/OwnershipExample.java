package group.zn.zero.examples.recipes.ownership;

import group.zn.zero.ownership.InMemoryFencedStateStore;
import group.zn.zero.ownership.InMemoryOwnershipStore;
import group.zn.zero.ownership.OwnershipException;
import group.zn.zero.ownership.OwnershipResource;
import group.zn.zero.ownership.ProcessIncarnation;
import group.zn.zero.ownership.StateCommitRequest;
import group.zn.zero.ownership.StateVersion;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 本地权威状态：租约 → CAS 提交 → 请求重放 → 主动交接 → 旧租约拒绝。
 * 内存租约与状态共用同一时钟及原子校验边界，不代表跨进程持久化实现。
 * @author zn
 */
public final class OwnershipExample {
    private OwnershipExample() { }

    /** @param args 未使用；隔离运行本地示例并打印，不修改外部状态。 */
    public static void main(final String[] args) {
        System.out.println("ownership=" + runFlow());
    }

    /**
     * 两个进程身份在同一本地存储中主动交接；所有输入状态均不可变。
     * @return 非空不可变摘要，线程安全；每次调用创建独立存储。
     * @throws IllegalStateException 本应拒绝的操作意外成功，或有效租约释放失败。
     * @throws OwnershipException 正常提交遇到非预期租约或版本错误。
     */
    public static Result runFlow() {
        var clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC);
        var ownership = new InMemoryOwnershipStore(clock);
        var state = new InMemoryFencedStateStore<Integer>(ownership, clock);
        var resource = new OwnershipResource("player", "1001", 1);
        var first = ownership.acquire(resource, new ProcessIncarnation("logic-a", "boot-a"), Duration.ofSeconds(30));
        var original = new StateCommitRequest<>(resource, first, StateVersion.initial(), "request-001", 100);
        var committed = state.commit(original);
        var replay = state.commit(original);
        String conflict = rejectionCode(() -> state.commit(
                new StateCommitRequest<>(resource, first, StateVersion.initial(), "request-002", 999)));
        if (!ownership.release(first)) {
            throw new IllegalStateException("当前租约应能主动释放");
        }
        var second = ownership.acquire(resource, new ProcessIncarnation("logic-b", "boot-b"), Duration.ofSeconds(30));
        try {
            // 新 owner 重新读取权威版本，不能沿用迁移前缓存中的版本。
            var current = state.read(resource).orElseThrow();
            var updated = state.commit(new StateCommitRequest<>(resource, second, current.version(), "request-003", 150));
            String staleWrite = rejectionCode(() -> state.commit(
                    new StateCommitRequest<>(resource, first, updated.newVersion(), "request-004", 999)));
            String staleReplay = rejectionCode(() -> state.commit(original));
            boolean oldReleaseRejected = !ownership.release(first);
            return new Result(committed.newVersion().value(), replay.replayed(), conflict,
                    first.epoch(), second.epoch(), updated.newVersion().value(),
                    state.read(resource).orElseThrow().value(), staleWrite, staleReplay, oldReleaseRejected);
        } finally {
            if (!ownership.release(second)) {
                throw new IllegalStateException("交接后的租约应能主动释放");
            }
        }
    }

    private static String rejectionCode(final Runnable operation) {
        try {
            operation.run();
        } catch (OwnershipException failure) {
            // 保留框架错误码；不依赖异常文案、不将未知失败转成成功。
            return failure.errorCode().code();
        }
        throw new IllegalStateException("不合法状态写入必须被拒绝");
    }

    /**
     * 不可变权威状态摘要；线程安全。
     * @param initialVersion 首次提交版本。
     * @param replayed 原请求是否命中收据。
     * @param conflictCode 版本冲突错误码。
     * @param firstEpoch 原租约代际。
     * @param secondEpoch 交接后代际。
     * @param finalVersion 最终版本。
     * @param finalCoins 最终积分，拒绝路径不得将其改为 999。
     * @param staleWriteCode 旧租约新写入的错误码。
     * @param staleReplayCode 旧租约重放的错误码。
     * @param oldReleaseRejected 旧租约是否无法释放新 owner。
     * @author zn
     */
    public record Result(long initialVersion, boolean replayed, String conflictCode, long firstEpoch,
                         long secondEpoch, long finalVersion, int finalCoins, String staleWriteCode,
                         String staleReplayCode, boolean oldReleaseRejected) { }
}
