package group.zn.zero.data.envelope;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.model.PageRequest;
import group.zn.zero.data.model.PageResult;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.data.repository.CrudRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 基于数据对象信封存储的通用 CRUD Repository。
 *
 * <p>该实现负责统一 Repository 语义、版本递增、payload 编解码和 ErrorCode 暴露；
 * 具体后端只需要实现 {@link ZeroDataEnvelopeStore}。
 *
 * @param <ID> 主键类型。
 * @param <T> 实体类型。
 * @author zn
 */
public final class ZeroDataEnvelopeCrudRepository<ID, T extends VersionedEntity<ID>>
        implements CrudRepository<ID, T> {

    /**
     * 实体 codec。
     */
    private final ZeroDataEntityCodec<ID, T> entityCodec;

    /**
     * 信封存储。
     */
    private final ZeroDataEnvelopeStore store;

    /**
     * 创建基于信封存储的 CRUD Repository。
     *
     * @param entityCodec 实体 codec；不可为空。
     * @param store 信封存储；不可为空。
     * @throws NullPointerException 当必要参数为空时抛出。
     */
    public ZeroDataEnvelopeCrudRepository(
            final ZeroDataEntityCodec<ID, T> entityCodec,
            final ZeroDataEnvelopeStore store) {
        this.entityCodec = Objects.requireNonNull(entityCodec, "entityCodec");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * 根据 ID 查询对象。
     *
     * @param id 对象 ID；不可为空。
     * @return 查询结果；为空表示不存在；线程安全。
     */
    @Override
    public synchronized CompletionStage<Optional<T>> findById(final ID id) {
        try {
            String encodedId = entityCodec.encodeId(Objects.requireNonNull(id, "id"));
            return CompletableFuture.completedFuture(store.findById(encodedId).map(entityCodec::decode));
        } catch (RuntimeException ex) {
            return failedFuture(errorCode(ex, DataErrorCode.READ_FAILED), message(ex, "find data entity failed"), ex);
        }
    }

    /**
     * 保存对象。
     *
     * @param entity 对象；不可为空。
     * @return 保存完成信号；不可为空；线程安全。
     */
    @Override
    public synchronized CompletionStage<Void> save(final T entity) {
        try {
            T current = checkedEntity(entityCodec.prepareForSave(entity));
            ID id = entityCodec.idOf(current);
            long expectedVersion = current.version();
            T persisted = entityCodec.withVersion(current, expectedVersion + 1L);
            boolean saved = store.saveIfVersion(entityCodec.encode(persisted), expectedVersion);
            if (!saved) {
                return failedFuture(DataErrorCode.VERSION_CONFLICT,
                        "version conflict for id=" + entityCodec.encodeId(id)
                                + ", expected=" + expectedVersion,
                        null);
            }
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException ex) {
            return failedFuture(errorCode(ex, DataErrorCode.WRITE_FAILED), message(ex, "save data entity failed"), ex);
        }
    }

    /**
     * 查询所有对象。
     *
     * @return 查询结果列表；不可为空；可能为空；线程安全。
     */
    @Override
    public synchronized CompletionStage<List<T>> findAll() {
        try {
            return CompletableFuture.completedFuture(decodeAll(store.findAll()));
        } catch (RuntimeException ex) {
            return failedFuture(errorCode(ex, DataErrorCode.READ_FAILED), message(ex, "find all data failed"), ex);
        }
    }

    /**
     * 根据 ID 列表批量查询。
     *
     * @param ids 对象 ID 列表；不可为空。
     * @return 查询结果列表；不可为空；可能为空；线程安全。
     */
    @Override
    public synchronized CompletionStage<List<T>> findByIds(final Collection<ID> ids) {
        try {
            Objects.requireNonNull(ids, "ids");
            List<T> results = new ArrayList<>(ids.size());
            for (ID id : ids) {
                String encodedId = entityCodec.encodeId(Objects.requireNonNull(id, "id"));
                store.findById(encodedId).map(entityCodec::decode).ifPresent(results::add);
            }
            return CompletableFuture.completedFuture(List.copyOf(results));
        } catch (RuntimeException ex) {
            return failedFuture(errorCode(ex, DataErrorCode.READ_FAILED), message(ex, "find data batch failed"), ex);
        }
    }

    /**
     * 批量保存对象。
     *
     * @param entities 对象集合；不可为空。
     * @return 保存完成信号；不可为空；线程安全。
     */
    @Override
    public synchronized CompletionStage<Void> saveAll(final Collection<T> entities) {
        try {
            Objects.requireNonNull(entities, "entities");
            for (T entity : entities) {
                save(entity).toCompletableFuture().join();
            }
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException ex) {
            return failedFuture(errorCode(ex, DataErrorCode.WRITE_FAILED), message(ex, "save data batch failed"), ex);
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
        try {
            store.deleteById(entityCodec.encodeId(Objects.requireNonNull(id, "id")));
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException ex) {
            return failedFuture(errorCode(ex, DataErrorCode.DELETE_FAILED), message(ex, "delete data failed"), ex);
        }
    }

    /**
     * 批量删除对象。
     *
     * @param ids 对象 ID 列表；不可为空。
     * @return 删除完成信号；不可为空；线程安全。
     */
    @Override
    public synchronized CompletionStage<Void> deleteAll(final Collection<ID> ids) {
        try {
            Objects.requireNonNull(ids, "ids");
            for (ID id : ids) {
                store.deleteById(entityCodec.encodeId(Objects.requireNonNull(id, "id")));
            }
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException ex) {
            return failedFuture(errorCode(ex, DataErrorCode.DELETE_FAILED), message(ex, "delete data batch failed"), ex);
        }
    }

    /**
     * 判断对象是否存在。
     *
     * @param id 对象 ID；不可为空。
     * @return true 表示存在；线程安全。
     */
    @Override
    public synchronized CompletionStage<Boolean> existsById(final ID id) {
        try {
            return CompletableFuture.completedFuture(
                    store.findById(entityCodec.encodeId(Objects.requireNonNull(id, "id"))).isPresent());
        } catch (RuntimeException ex) {
            return failedFuture(errorCode(ex, DataErrorCode.READ_FAILED), message(ex, "exists data failed"), ex);
        }
    }

    /**
     * 统计对象数量。
     *
     * @return 对象数量；不可为空；线程安全。
     */
    @Override
    public synchronized CompletionStage<Long> count() {
        try {
            return CompletableFuture.completedFuture(store.count());
        } catch (RuntimeException ex) {
            return failedFuture(errorCode(ex, DataErrorCode.READ_FAILED), message(ex, "count data failed"), ex);
        }
    }

    /**
     * 查询分页结果。
     *
     * @param request 分页请求；不可为空。
     * @return 分页结果；不可为空；线程安全。
     */
    @Override
    public synchronized CompletionStage<PageResult<T>> findPage(final PageRequest request) {
        try {
            PageRequest current = Objects.requireNonNull(request, "request");
            List<T> values = decodeAll(store.findAll());
            int fromIndex = Math.min(current.offset(), values.size());
            int toIndex = Math.min(values.size(), current.offset() + current.limit());
            String nextCursor = toIndex < values.size() ? String.valueOf(toIndex) : null;
            return CompletableFuture.completedFuture(
                    new PageResult<>(values.subList(fromIndex, toIndex), nextCursor, values.size()));
        } catch (RuntimeException ex) {
            return failedFuture(errorCode(ex, DataErrorCode.READ_FAILED), message(ex, "page data failed"), ex);
        }
    }

    private List<T> decodeAll(final List<ZeroDataEnvelope> envelopes) {
        List<T> results = new ArrayList<>(envelopes.size());
        for (ZeroDataEnvelope envelope : envelopes) {
            results.add(entityCodec.decode(envelope));
        }
        return List.copyOf(results);
    }

    private T checkedEntity(final T entity) {
        T current = Objects.requireNonNull(entity, "entity");
        if (current.version() < 0L) {
            throw ZeroException.of(DataErrorCode.INVALID_ENTITY, "entity version must be non-negative", null);
        }
        return current;
    }

    private ErrorCode errorCode(final RuntimeException exception, final ErrorCode fallback) {
        if (exception instanceof ZeroException zeroException) {
            return zeroException.errorCode();
        }
        if (exception.getCause() instanceof ZeroException zeroException) {
            return zeroException.errorCode();
        }
        return fallback;
    }

    private String message(final RuntimeException exception, final String fallback) {
        if (exception.getCause() instanceof ZeroException zeroException) {
            return zeroException.getMessage();
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private <R> CompletionStage<R> failedFuture(
            final ErrorCode errorCode,
            final String message,
            final Throwable cause) {
        CompletableFuture<R> future = new CompletableFuture<>();
        future.completeExceptionally(ZeroException.of(errorCode, message, cause));
        return future;
    }
}
