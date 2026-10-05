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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 基于数据对象信封存储的通用 CRUD Repository。
 *
 * <p>该实现负责统一 Repository 语义、版本递增、payload 编解码和 ErrorCode 暴露；
 * 具体后端只需要实现 {@link ZeroDataEnvelopeStore}。
 * 本类可并发调用；存储 IO 在注入执行器中运行。保存前在调用线程编码快照，
 * 调用方必须在实体所属执行域调用，codec 必须可并发使用。返回实体的线程安全性由实体实现决定。
 * 完成回调可能在 IO 线程运行，修改 Actor 状态前必须回到对应 Actor 执行域。
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
     * 同步驱动的受管 IO 执行域。
     */
    private final Executor ioExecutor;

    /**
     * 创建基于信封存储的 CRUD Repository。
     *
     * @param entityCodec 实体 codec；不可为空。
     * @param store 信封存储；不可为空。
     * @param ioExecutor 受管 IO 执行器；生产同步驱动不可使用内联执行器；本类不负责关闭。
     * @throws NullPointerException 当必要参数为空时抛出。
     */
    public ZeroDataEnvelopeCrudRepository(
            final ZeroDataEntityCodec<ID, T> entityCodec,
            final ZeroDataEnvelopeStore store,
            final Executor ioExecutor) {
        this.entityCodec = Objects.requireNonNull(entityCodec, "entityCodec");
        this.store = Objects.requireNonNull(store, "store");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    /**
     * 根据 ID 查询对象。
     *
     * @param id 对象 ID；不可为空。
     * @return 查询结果；为空表示不存在；线程安全。
     */
    @Override
    public CompletionStage<Optional<T>> findById(final ID id) {
        return prepareAndSubmit(DataErrorCode.READ_FAILED, "find data entity failed",
                () -> encodeId(id), encoded -> store.findById(encoded).map(entityCodec::decode));
    }

    /**
     * 保存对象。
     *
     * @param entity 对象；不可为空。
     * @return 保存完成信号；不可为空；线程安全。
     */
    @Override
    public CompletionStage<Void> save(final T entity) {
        return prepareAndSubmit(DataErrorCode.WRITE_FAILED, "save data entity failed",
                () -> prepareWrite(entity), write -> {
            boolean saved = store.saveIfVersion(write.envelope(), write.expectedVersion());
            if (!saved) {
                throw ZeroException.of(DataErrorCode.VERSION_CONFLICT,
                        "version conflict for id=" + write.envelope().id()
                                + ", expected=" + write.expectedVersion(), null);
            }
            return null;
        });
    }

    /**
     * 查询所有对象。
     *
     * @return 不可变列表；可能为空；顺序由 store 决定；可并发读取，元素线程安全性由实体决定。
     */
    @Override
    public CompletionStage<List<T>> findAll() {
        return submit(DataErrorCode.READ_FAILED, "find all data failed", () -> decodeAll(store.findAll()));
    }

    /**
     * 根据 ID 列表批量查询。
     *
     * @param ids 对象 ID 列表；不可为空。
     * @return 不可变列表；可能为空；按输入顺序返回，缺失项省略、重复项保留；元素线程安全性由实体决定。
     */
    @Override
    public CompletionStage<List<T>> findByIds(final Collection<ID> ids) {
        return prepareAndSubmit(DataErrorCode.READ_FAILED, "find data batch failed",
                () -> encodeIds(ids), encodedIds -> {
            Map<String, T> decoded = new HashMap<>();
            for (ZeroDataEnvelope envelope : store.findByIds(encodedIds)) {
                decoded.putIfAbsent(envelope.id(), entityCodec.decode(envelope));
            }
            List<T> results = new ArrayList<>(encodedIds.size());
            for (String encodedId : encodedIds) {
                T value = decoded.get(encodedId);
                if (value != null) {
                    results.add(value);
                }
            }
            return List.copyOf(results);
        });
    }

    /**
     * 批量保存对象。
     *
     * @param entities 对象集合；不可为空。
     * @return 保存完成信号；不可为空；线程安全；失败可能已有部分提交，需重读后重试。
     */
    @Override
    public CompletionStage<Void> saveAll(final Collection<T> entities) {
        return prepareAndSubmit(DataErrorCode.WRITE_FAILED, "save data batch failed", () -> {
            Collection<T> currentEntities = Objects.requireNonNull(entities, "entities");
            List<ZeroDataEnvelopeStore.ConditionalWrite> writes = new ArrayList<>(currentEntities.size());
            for (T entity : currentEntities) {
                writes.add(prepareWrite(entity));
            }
            return List.copyOf(writes);
        }, writes -> {
            List<Boolean> saved = store.saveIfVersionBatch(writes);
            if (saved.size() != writes.size()) {
                throw new IllegalStateException("store returned mismatched batch result size");
            }
            for (int index = 0; index < saved.size(); index++) {
                if (!Boolean.TRUE.equals(saved.get(index))) {
                    throw ZeroException.of(DataErrorCode.VERSION_CONFLICT,
                            "version conflict for id=" + writes.get(index).envelope().id(), null);
                }
            }
            return null;
        });
    }

    /**
     * 根据 ID 删除对象。
     *
     * @param id 对象 ID；不可为空。
     * @return 删除完成信号；不可为空；线程安全。
     */
    @Override
    public CompletionStage<Void> deleteById(final ID id) {
        return prepareAndSubmit(DataErrorCode.DELETE_FAILED, "delete data failed", () -> encodeId(id), encoded -> {
            store.deleteById(encoded);
            return null;
        });
    }

    /**
     * 批量删除对象。
     *
     * @param ids 对象 ID 列表；不可为空。
     * @return 删除完成信号；不可为空；线程安全。
     */
    @Override
    public CompletionStage<Void> deleteAll(final Collection<ID> ids) {
        return prepareAndSubmit(DataErrorCode.DELETE_FAILED, "delete data batch failed",
                () -> encodeIds(ids), encodedIds -> {
            store.deleteAll(encodedIds);
            return null;
        });
    }

    /**
     * 判断对象是否存在。
     *
     * @param id 对象 ID；不可为空。
     * @return true 表示存在；线程安全。
     */
    @Override
    public CompletionStage<Boolean> existsById(final ID id) {
        return prepareAndSubmit(DataErrorCode.READ_FAILED, "exists data failed",
                () -> encodeId(id), encoded -> store.findById(encoded).isPresent());
    }

    /**
     * 统计对象数量。
     *
     * @return 对象数量；不可为空；线程安全。
     */
    @Override
    public CompletionStage<Long> count() {
        return submit(DataErrorCode.READ_FAILED, "count data failed", store::count);
    }

    /**
     * 查询分页结果。
     *
     * @param request 分页请求；不可为空。
     * @return 分页结果；不可为空；线程安全。
     */
    @Override
    public CompletionStage<PageResult<T>> findPage(final PageRequest request) {
        return submit(DataErrorCode.READ_FAILED, "page data failed", () -> {
            PageRequest current = Objects.requireNonNull(request, "request");
            PageResult<ZeroDataEnvelope> page = store.findPage(current);
            return new PageResult<>(decodeAll(page.items()), page.nextCursor(), page.total());
        });
    }

    /**
     * 将同步存储调用放入受管 IO 执行域。
     *
     * <p>每次调用独立提交，Repository 不以全局锁或尾链限制并发；后端的 CAS 负责版本语义。
     *
     * @param fallback 默认错误码。
     * @param fallbackMessage 默认错误信息。
     * @param operation 同步存储操作；不可为空。
     * @param <R> 返回类型。
     * @return 异步结果；不可为空；线程安全。
     */
    private <R> CompletionStage<R> submit(
            final ErrorCode fallback,
            final String fallbackMessage,
            final Supplier<R> operation) {
        CompletableFuture<R> result = new CompletableFuture<>();
        try {
            ioExecutor.execute(() -> execute(operation, fallback, fallbackMessage, result));
        } catch (RuntimeException | Error failure) {
            result.completeExceptionally(mappedFailure(failure, fallback, fallbackMessage));
        }
        return result;
    }

    /** 在受管 IO 线程运行实际同步操作。 */
    private <R> void execute(
            final Supplier<R> operation,
            final ErrorCode fallback,
            final String fallbackMessage,
            final CompletableFuture<R> result) {
        try {
            result.complete(operation.get());
        } catch (RuntimeException | Error failure) {
            result.completeExceptionally(mappedFailure(failure, fallback, fallbackMessage));
        }
    }

    /** 在调用者所属执行域编码不可变快照，避免 IO 线程读取正在变更的 Actor 实体。 */
    private ZeroDataEnvelopeStore.ConditionalWrite prepareWrite(final T entity) {
        T current = checkedEntity(entityCodec.prepareForSave(entity));
        long expectedVersion = current.version();
        T persisted = entityCodec.withVersion(current, expectedVersion + 1L);
        return new ZeroDataEnvelopeStore.ConditionalWrite(entityCodec.encode(persisted), expectedVersion);
    }

    /** 在调用者执行域编码 ID，避免异步读取可变复合键。 */
    private String encodeId(final ID id) {
        return entityCodec.encodeId(Objects.requireNonNull(id, "id"));
    }

    /** 批量编码输入快照，保持顺序和重复项，禁止将调用者集合捕获到 IO 任务。 */
    private List<String> encodeIds(final Collection<ID> ids) {
        Collection<ID> current = Objects.requireNonNull(ids, "ids");
        List<String> encoded = new ArrayList<>(current.size());
        for (ID id : current) {
            encoded.add(encodeId(id));
        }
        return List.copyOf(encoded);
    }

    /** 准备阶段只有 CPU 操作；所有存储 IO 均经 submit 进入受管执行器。 */
    private <P, R> CompletionStage<R> prepareAndSubmit(
            final ErrorCode fallback, final String message,
            final Supplier<P> preparation, final Function<P, R> operation) {
        try {
            P prepared = preparation.get();
            return submit(fallback, message, () -> operation.apply(prepared));
        } catch (RuntimeException | Error failure) {
            return CompletableFuture.failedFuture(mappedFailure(failure, fallback, message));
        }
    }

    private ZeroException mappedFailure(final Throwable failure, final ErrorCode fallback, final String message) {
        return ZeroException.of(errorCode(failure, fallback), message(failure, message), failure);
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
        if (current.version() < 0L || current.version() == Long.MAX_VALUE) {
            throw ZeroException.of(DataErrorCode.INVALID_ENTITY, "entity version must be incrementable", null);
        }
        return current;
    }

    private ErrorCode errorCode(final Throwable exception, final ErrorCode fallback) {
        if (exception instanceof ZeroException zeroException) {
            return zeroException.errorCode();
        }
        if (exception.getCause() instanceof ZeroException zeroException) {
            return zeroException.errorCode();
        }
        return fallback;
    }

    private String message(final Throwable exception, final String fallback) {
        if (exception.getCause() instanceof ZeroException zeroException) {
            return zeroException.getMessage();
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

}
