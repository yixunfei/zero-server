package group.zn.zero.ownership;

import java.util.Optional;

/** 在权威存储端执行 ownership fencing、CAS 和请求幂等的状态端口。 */
public interface FencedStateStore<T> {
    /** 读取资源当前快照；不存在时返回空。 */
    Optional<StateSnapshot<T>> read(OwnershipResource resource);

    /** 校验租约并提交状态；旧 epoch、错误版本或请求冲突必须拒绝。 */
    CommitResult<T> commit(StateCommitRequest<T> request);
}
