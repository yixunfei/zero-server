package group.zn.zero.data.envelope;

import java.util.List;
import java.util.Optional;

/**
 * 数据对象信封存储接口。
 *
 * <p>该接口是 Repository 和具体后端之间的最小桥接层。实现可以落到 MongoDB document、
 * Redis snapshot/journal、本地磁盘或 PostgreSQL 通用对象表。
 *
 * @author zn
 */
public interface ZeroDataEnvelopeStore {

    /**
     * 根据编码 ID 查询信封。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @return 查询结果；不可为空；可能为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 查询失败时抛出，必须绑定 ErrorCode。
     */
    Optional<ZeroDataEnvelope> findById(String id);

    /**
     * 查询全部信封。
     *
     * @return 信封列表；不可为空；可能为空；有序性和线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 查询失败时抛出，必须绑定 ErrorCode。
     */
    List<ZeroDataEnvelope> findAll();

    /**
     * 保存信封。
     *
     * @param envelope 信封；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 保存失败时抛出，必须绑定 ErrorCode。
     */
    void save(ZeroDataEnvelope envelope);

    /**
     * 按期望版本条件保存信封。
     *
     * <p>该方法承载 Repository 的乐观锁语义。`expectedVersion == 0` 表示仅当对象不存在时创建；
     * `expectedVersion > 0` 表示仅当后端当前版本等于该值时更新。真实 driver-backed store 必须用
     * 后端原子条件写实现，避免多实例并发下先读后写导致丢更新。
     *
     * @param envelope 待保存信封；不可为空。
     * @param expectedVersion 期望当前版本；必须大于等于 0。
     * @return true 表示保存成功；false 表示版本条件不满足；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 保存失败时抛出，必须绑定 ErrorCode。
     */
    default boolean saveIfVersion(final ZeroDataEnvelope envelope, final long expectedVersion) {
        ZeroDataEnvelope current = java.util.Objects.requireNonNull(envelope, "envelope");
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        Optional<ZeroDataEnvelope> previous = findById(current.id());
        if (expectedVersion == 0L) {
            if (previous.isPresent()) {
                return false;
            }
        } else if (previous.isEmpty() || previous.orElseThrow().version() != expectedVersion) {
            return false;
        }
        save(current);
        return true;
    }

    /**
     * 根据编码 ID 删除信封。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 删除失败时抛出，必须绑定 ErrorCode。
     */
    void deleteById(String id);

    /**
     * 统计信封数量。
     *
     * @return 信封数量；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 统计失败时抛出，必须绑定 ErrorCode。
     */
    long count();
}
