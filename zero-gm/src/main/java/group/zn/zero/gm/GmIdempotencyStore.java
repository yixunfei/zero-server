package group.zn.zero.gm;

import java.time.Duration;
import java.time.Instant;

/**
 * 应用拥有的副作用幂等边界；实现须线程安全，跨进程使用共享持久化及稳定部署指纹密钥。
 * 在途 claim 不得因终态保留预算耗尽而再次认领；崩溃恢复须先确认业务状态。
 * 完成与失败记账必须原子校验 key/fingerprint/IN_PROGRESS；失败确认不等于业务回滚。
 * @author zn
 */
public interface GmIdempotencyStore {
    /**
     * 原子认领或读取已有状态，仅终态允许到期回收；不执行外部业务。
     * @param key 非空请求键。 @param fingerprint 非空安全指纹。 @param now 当前绝对时间。
     * @param ttl 正数终态保留预算提示，不是执行超时。
     * @return 非空不可变认领结果；COMPLETED 必须携带原响应，其他状态不携带响应。
     * @throws RuntimeException 参数无效或存储故障；此时调用方不得执行业务。
     */
    Claim claim(String key, String fingerprint, Instant now, Duration ttl);

    /**
     * 将当前 claim 原子转为 COMPLETED，保留成功或无副作用业务拒绝响应。
     * @param key 请求键。 @param fingerprint 认领指纹。 @param response 已确定的业务结果。
     * @param expiresAt 实际完成时刻加终态保留预算。线程安全，修改存储。
     * @throws RuntimeException 缺少/冲突/已终态的 claim 或存储故障；写后响应丢失也可能抛出，禁止盲重试业务。
     */
    void complete(String key, String fingerprint, GmOperationResponse response, Instant expiresAt);

    /**
     * 将当前 claim 原子转为 FAILED，阻止保留期内重新执行业务，不声称业务已回滚。
     * @param key 请求键。 @param fingerprint 认领指纹。 @param expiresAt 实际结束时刻加保留预算。
     * 线程安全，修改存储；进程崩溃等未确认在途结果需应用恢复。
     * @throws RuntimeException 缺少/冲突/已终态的 claim 或存储故障；不得覆盖原业务故障。
     */
    void fail(String key, String fingerprint, Instant expiresAt);

    record Claim(State state, GmOperationResponse response) {
        public Claim {
            if (state == null) {
                throw new NullPointerException("state");
            }
            if ((state == State.COMPLETED) != (response != null)) {
                throw new IllegalArgumentException("completed claim must carry response");
            }
        }
    }

    enum State {
        CLAIMED,
        /** 未认领且未执行业务；容量背压，可在容量释放后重试。 */
        CAPACITY_REJECTED,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        CONFLICT
    }
}
