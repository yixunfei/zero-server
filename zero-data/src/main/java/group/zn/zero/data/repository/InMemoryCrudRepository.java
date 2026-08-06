package group.zn.zero.data.repository;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.model.PageRequest;
import group.zn.zero.data.model.PageResult;
import group.zn.zero.data.model.VersionedEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 本地内存仓库实现。
 *
 * <p>支持批量读写、乐观锁版本和分页查询，用于阶段 2C 的本地适配器与 smoke test。
 *
 * @param <ID> 主键类型。
 * @param <T> 实体类型。
 * @author zn
 */
public final class InMemoryCrudRepository<ID, T extends VersionedEntity<ID>> implements CrudRepository<ID, T> {

    /**
     * 数据存储。
     */
    private final Map<ID, T> storage = new LinkedHashMap<>();

    /**
     * 根据 ID 查询对象。
     *
     * @param id 对象 ID；不可为空。
     * @return 查询结果；为空表示不存在；线程安全。
     */
    @Override
    public synchronized CompletionStage<Optional<T>> findById(final ID id) {
        Objects.requireNonNull(id, "id");
        return CompletableFuture.completedFuture(Optional.ofNullable(storage.get(id)));
    }

    /**
     * 保存对象。
     *
     * @param entity 对象；不可为空。
     * @return 保存完成信号；不可为空；线程安全。
     */
    @Override
    public synchronized CompletionStage<Void> save(final T entity) {
        T current = checkedEntity(entity);
        ID id = current.id();
        T previous = storage.get(id);
        if (previous == null) {
            storage.put(id, castEntity(current.withVersion(1)));
            return CompletableFuture.completedFuture(null);
        }
        if (previous.version() != current.version()) {
            return failedFuture(DataErrorCode.VERSION_CONFLICT,
                    "version conflict for id=" + id + ", expected=" + previous.version() + ", actual=" + current.version());
        }
        storage.put(id, castEntity(current.withVersion(previous.version() + 1)));
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 查询所有对象。
     *
     * @return 查询结果列表；不可为空；可能为空；线程安全。
     */
    @Override
    public synchronized CompletionStage<List<T>> findAll() {
        return CompletableFuture.completedFuture(List.copyOf(storage.values()));
    }

    /**
     * 根据 ID 列表批量查询。
     *
     * @param ids 对象 ID 列表；不可为空。
     * @return 查询结果列表；不可为空；可能为空；线程安全。
     */
    @Override
    public synchronized CompletionStage<List<T>> findByIds(final Collection<ID> ids) {
        Objects.requireNonNull(ids, "ids");
        List<T> results = new ArrayList<>(ids.size());
        for (ID id : ids) {
            T value = storage.get(Objects.requireNonNull(id, "id"));
            if (value != null) {
                results.add(value);
            }
        }
        return CompletableFuture.completedFuture(List.copyOf(results));
    }

    /**
     * 批量保存对象。
     *
     * @param entities 对象集合；不可为空。
     * @return 保存完成信号；不可为空；线程安全。
     */
    @Override
    public synchronized CompletionStage<Void> saveAll(final Collection<T> entities) {
        Objects.requireNonNull(entities, "entities");
        try {
            for (T entity : entities) {
                save(entity).toCompletableFuture().join();
            }
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException ex) {
            if (ex instanceof ZeroException zeroException) {
                return failedFuture(zeroException.errorCode(),
                        zeroException.getMessage() == null ? "batch save failed" : zeroException.getMessage());
            }
            return failedFuture(DataErrorCode.WRITE_FAILED,
                    ex.getMessage() == null ? "batch save failed" : ex.getMessage());
        }
    }

    /**
     * 根据 ID 删除对象。
     *
     * @param id 对象 ID；不可为空。
     * @return 删除完成信号；不可为空；线程安全。
     */
    @Override
    public synchronized CompletionStage<Void> deleteById(final ID id) {
        Objects.requireNonNull(id, "id");
        storage.remove(id);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 批量删除对象。
     *
     * @param ids 对象 ID 列表；不可为空。
     * @return 删除完成信号；不可为空；线程安全。
     */
    @Override
    public synchronized CompletionStage<Void> deleteAll(final Collection<ID> ids) {
        Objects.requireNonNull(ids, "ids");
        for (ID id : ids) {
            storage.remove(Objects.requireNonNull(id, "id"));
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 判断对象是否存在。
     *
     * @param id 对象 ID；不可为空。
     * @return true 表示存在；线程安全。
     */
    @Override
    public synchronized CompletionStage<Boolean> existsById(final ID id) {
        Objects.requireNonNull(id, "id");
        return CompletableFuture.completedFuture(storage.containsKey(id));
    }

    /**
     * 统计对象数量。
     *
     * @return 对象数量；不可为空；线程安全。
     */
    @Override
    public synchronized CompletionStage<Long> count() {
        return CompletableFuture.completedFuture((long) storage.size());
    }

    /**
     * 查询分页结果。
     *
     * @param request 分页请求；不可为空。
     * @return 分页结果；不可为空；线程安全。
     */
    @Override
    public synchronized CompletionStage<PageResult<T>> findPage(final PageRequest request) {
        PageRequest current = Objects.requireNonNull(request, "request");
        List<T> values = new ArrayList<>(storage.values());
        int fromIndex = Math.min(current.offset(), values.size());
        int toIndex = Math.min(values.size(), current.offset() + current.limit());
        List<T> items = values.subList(fromIndex, toIndex);
        String nextCursor = toIndex < values.size() ? String.valueOf(toIndex) : null;
        return CompletableFuture.completedFuture(new PageResult<>(items, nextCursor, values.size()));
    }

    /**
     * 返回当前快照。
     *
     * @return 不可变、有序、可能为空、线程安全的实体列表。
     */
    public synchronized List<T> snapshot() {
        return List.copyOf(storage.values());
    }

    private T checkedEntity(final T entity) {
        T current = Objects.requireNonNull(entity, "entity");
        if (current.id() == null) {
            throw ZeroException.of(DataErrorCode.INVALID_ENTITY, "entity id must not be null", null);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private T castEntity(final VersionedEntity<ID> entity) {
        return (T) entity;
    }

    private <R> CompletionStage<R> failedFuture(final group.zn.zero.core.error.ErrorCode errorCode, final String message) {
        CompletableFuture<R> future = new CompletableFuture<>();
        future.completeExceptionally(ZeroException.of(errorCode, message, null));
        return future;
    }
}
