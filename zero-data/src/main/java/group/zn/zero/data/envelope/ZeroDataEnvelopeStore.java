package group.zn.zero.data.envelope;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import group.zn.zero.data.model.PageRequest;
import group.zn.zero.data.model.PageResult;

/**
 * 数据对象信封存储接口。
 *
 * <p>该接口是 Repository 和具体后端之间的最小桥接层。实现可以落到 MongoDB document、
 * Redis snapshot/journal、本地磁盘或 PostgreSQL 通用对象表。
 * 实现必须支持不同请求并发调用；Repository 不在网络 IO 外持有全局锁。
 *
 * @author zn
 */
public interface ZeroDataEnvelopeStore {

    /** 带期望版本的批量写入项。 */
    record ConditionalWrite(ZeroDataEnvelope envelope, long expectedVersion) {
        /** 校验不可空信封和非负期望版本，不改变信封内容。 */
        public ConditionalWrite {
            java.util.Objects.requireNonNull(envelope, "envelope");
            if (expectedVersion < 0L) {
                throw new IllegalArgumentException("expectedVersion must be non-negative");
            }
        }
    }

    /**
     * 根据编码 ID 查询信封。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @return 查询结果；不可为空；可能为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 查询失败时抛出，必须绑定 ErrorCode。
     */
    Optional<ZeroDataEnvelope> findById(String id);

    /**
     * 批量查询；默认逐项调用，具体驱动覆盖为有界批量协议。
     * @param ids 编码 ID；不可空，元素不可空；允许重复。
     * @return 不可变、有序、可为空列表；按输入顺序返回，缺失项省略、重复项保留；可并发读取。
     * @throws group.zn.zero.core.error.ZeroException 查询失败时绑定 READ_FAILED。
     */
    default List<ZeroDataEnvelope> findByIds(final Collection<String> ids) {
        java.util.Objects.requireNonNull(ids, "ids");
        java.util.ArrayList<ZeroDataEnvelope> result = new java.util.ArrayList<>(ids.size());
        for (String id : ids) {
            findById(java.util.Objects.requireNonNull(id, "id")).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    /**
     * 查询全部信封。
     *
     * @return 信封列表；不可为空；可能为空；有序性和线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 查询失败时抛出，必须绑定 ErrorCode。
     */
    List<ZeroDataEnvelope> findAll();

    /**
     * 查询一页；生产驱动只读取页内 payload，默认回退使用 findAll，仅适合小型内存实现。
     * @param request offset/limit 请求；不可空。
     * @return 不可变页，items 可为空；驱动按 ID 排序，并发写入时 total/items 可以弱一致。
     * @throws group.zn.zero.core.error.ZeroException 查询失败时绑定 READ_FAILED。
     */
    default PageResult<ZeroDataEnvelope> findPage(final PageRequest request) {
        PageRequest current = java.util.Objects.requireNonNull(request, "request");
        List<ZeroDataEnvelope> values = findAll();
        int fromIndex = Math.min(current.offset(), values.size());
        int toIndex = (int) Math.min((long) values.size(), (long) current.offset() + current.limit());
        String nextCursor = toIndex < values.size() ? String.valueOf(toIndex) : null;
        return new PageResult<>(values.subList(fromIndex, toIndex), nextCursor, values.size());
    }

    /**
     * 原子保存快照；允许对象不存在、同版重放或更高版本，拒绝覆盖更高版本。
     * 同版内容可替换；业务乐观锁必须使用 saveIfVersion，不能以本方法代替 CAS。
     *
     * @param envelope 信封；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 保存失败时抛出，必须绑定 ErrorCode。
     */
    void save(ZeroDataEnvelope envelope);

    /**
     * 按期望版本条件保存信封。
     *
     * <p>该方法承载 Repository 的乐观锁语义。`expectedVersion == 0` 表示仅当对象不存在时创建；
     * `expectedVersion > 0` 表示仅当后端当前版本等于该值时更新。存储实现必须覆盖此默认方法并用
     * 后端原子条件写实现；默认实现直接失败，避免多实例并发下先读后写导致丢更新。
     *
     * @param envelope 待保存信封；不可为空。
     * @param expectedVersion 期望当前版本；必须大于等于 0。
     * @return true 表示保存成功；false 表示版本条件不满足；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 保存失败时抛出，必须绑定 ErrorCode。
     */
    default boolean saveIfVersion(final ZeroDataEnvelope envelope, final long expectedVersion) {
        java.util.Objects.requireNonNull(envelope, "envelope");
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        throw group.zn.zero.core.error.ZeroException.of(
                group.zn.zero.data.error.DataErrorCode.ATOMIC_WRITE_UNSUPPORTED,
                "store must implement atomic saveIfVersion", null);
    }

    /**
     * 批量条件保存；每项 CAS 原子，整个批次不保证事务原子性，保留输入执行顺序。
     * @param writes 不可空，元素不可空；允许空集合。
     * @return 不可变、有序、可为空的逐项结果，长度与输入相同；false 表示该项冲突。
     * @throws group.zn.zero.core.error.ZeroException 驱动无法准确报告逐项结果时可抛 VERSION_CONFLICT；
     *     IO 失败绑定 WRITE_FAILED。任何异常都可能已有部分项提交，调用方必须重新读取确认。
     */
    default List<Boolean> saveIfVersionBatch(final Collection<ConditionalWrite> writes) {
        java.util.Objects.requireNonNull(writes, "writes");
        java.util.ArrayList<Boolean> result = new java.util.ArrayList<>(writes.size());
        for (ConditionalWrite write : writes) {
            ConditionalWrite current = java.util.Objects.requireNonNull(write, "write");
            result.add(saveIfVersion(current.envelope(), current.expectedVersion()));
        }
        return List.copyOf(result);
    }

    /**
     * 根据编码 ID 删除信封。
     *
     * @param id 编码后的存储 ID；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 删除失败时抛出，必须绑定 ErrorCode。
     */
    void deleteById(String id);

    /**
     * 批量删除；默认逐条删除，具体驱动覆盖为有界批量协议；线程安全。
     * @param ids 编码 ID；不可空，元素不可空；允许空集合和重复；缺失项忽略。
     * @throws group.zn.zero.core.error.ZeroException 失败时绑定 DELETE_FAILED 或 VERSION_CONFLICT；
     *     整个批次不保证事务原子性，异常时可能已有部分项删除。
     */
    default void deleteAll(final Collection<String> ids) {
        java.util.Objects.requireNonNull(ids, "ids");
        for (String id : ids) {
            deleteById(java.util.Objects.requireNonNull(id, "id"));
        }
    }

    /**
     * 统计信封数量。
     *
     * @return 信封数量；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 统计失败时抛出，必须绑定 ErrorCode。
     */
    long count();
}
