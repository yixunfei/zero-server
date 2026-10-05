package group.zn.zero.data.envelope;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.mapping.ZeroDataObjectMetadata;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.data.repository.CrudRepository;
import group.zn.zero.data.repository.RepositoryDefinition;
import group.zn.zero.data.repository.RepositoryFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 延迟创建信封仓库的线程安全工厂；创建阶段可能初始化后端，应由组合根预热。
 * 外部执行器和客户端由调用方管理，CRUD 的同步 Store IO 交给注入执行器。
 *
 * @author zn
 */
public final class EnvelopeRepositoryFactory implements RepositoryFactory, AutoCloseable {
    /** 按实体元数据创建存储，创建过程在工厂内串行。 */
    private final Function<ZeroDataObjectMetadata, ZeroDataEnvelopeStore> stores;
    /** 外部拥有的受管 IO 执行域。 */
    private final Executor ioExecutor;
    /** 仅关闭本工厂拥有的资源；外部客户端保持调用方所有权。 */
    private final Runnable closeOwnedResources;
    /** 资源清理只执行一次。 */
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 并发访问与关闭的生命周期边界。 */
    private final RepositoryScope scope = new RepositoryScope();
    /** 按逻辑名称缓存的仓库，仅在工厂锁内访问。 */
    private final Map<String, Entry> repositories = new HashMap<>();
    /** 物理存储的唯一绑定，仅在工厂锁内访问。 */
    private final Map<StorageKey, RepositoryDefinition<?, ?>> storageDefinitions = new HashMap<>();

    /** 创建共享 IO 执行器的工厂；不拥有注入执行器或 Store 所用外部客户端，线程安全。
     * @param stores 并发安全 Store 工厂；不可空。 @param ioExecutor IO 执行器；不可空。 */
    public EnvelopeRepositoryFactory(
            final Function<ZeroDataObjectMetadata, ZeroDataEnvelopeStore> stores,
            final Executor ioExecutor) {
        this(stores, ioExecutor, () -> { });
    }

    /** 创建工厂并指定自有资源清理；线程安全，close 等待正在执行的 Store 操作后执行清理。
     * @param stores Store 工厂；不可空。 @param ioExecutor 外部拥有的 IO 执行器；不可空。
     * @param closeOwnedResources 关闭工厂自有资源的动作；不可空且只调用一次。 */
    public EnvelopeRepositoryFactory(
            final Function<ZeroDataObjectMetadata, ZeroDataEnvelopeStore> stores,
            final Executor ioExecutor, final Runnable closeOwnedResources) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.closeOwnedResources = Objects.requireNonNull(closeOwnedResources, "closeOwnedResources");
    }

    /** 创建线程安全的内存工厂。
     * @param ioExecutor 调用执行器；测试/纯内存可内联。
     * @return 工厂；不可空，不拥有执行器。 */
    public static EnvelopeRepositoryFactory inMemory(final Executor ioExecutor) {
        return new EnvelopeRepositoryFactory(metadata -> new InMemoryEnvelopeStore(), ioExecutor);
    }

    /**
     * 创建或复用定义完全相同的仓库；线程安全，首次创建可能同步初始化存储。
     * @param definition 不可变仓库定义；不可为空。
     * @param <ID> 主键类型。
     * @param <T> 实体类型。
     * @return 可并发调用的仓库；不可为空，生命周期属于本工厂。
     * @throws NullPointerException 定义为空时抛出。
     * @throws IllegalArgumentException 名称或物理存储已绑定其他定义时抛出。
     * @throws ZeroException 工厂已关闭或后端初始化失败时绑定 BACKEND_UNAVAILABLE。
     */
    @Override
    public <ID, T extends VersionedEntity<ID>> CrudRepository<ID, T> create(
            final RepositoryDefinition<ID, T> definition) {
        Objects.requireNonNull(definition, "definition");
        return scope.access(() -> createScoped(definition));
    }

    @SuppressWarnings("unchecked")
    private synchronized <ID, T extends VersionedEntity<ID>> CrudRepository<ID, T> createScoped(
            final RepositoryDefinition<ID, T> definition) {
        Entry previous = repositories.get(definition.name());
        if (previous != null) {
            if (!previous.definition().equals(definition)) {
                throw new IllegalArgumentException("repository name is already bound to another definition");
            }
            return (CrudRepository<ID, T>) previous.repository();
        }
        var key = new StorageKey(definition.metadata().namespace(), definition.metadata().collection());
        if (storageDefinitions.containsKey(key)) {
            throw new IllegalArgumentException("storage namespace/collection is already bound to another repository");
        }
        var codec = new ZeroDataEntityCodec<ID, T>(definition.metadata(), definition.codec(), definition.codecVersion());
        ZeroDataEnvelopeStore store;
        try {
            store = Objects.requireNonNull(stores.apply(definition.metadata()), "envelope store");
        } catch (RuntimeException failure) {
            throw ZeroException.of(DataErrorCode.BACKEND_UNAVAILABLE, "repository storage creation failed", null);
        }
        CrudRepository<ID, T> repository = new ZeroDataEnvelopeCrudRepository<>(
                codec, new ScopedEnvelopeStore(scope, store), ioExecutor);
        repositories.put(definition.name(), new Entry(definition, repository));
        storageDefinitions.put(key, definition);
        return repository;
    }

    /** 关闭当前作用域并清理自有资源；幂等、线程安全；排队但未进入 Store 的请求将失败。 */
    @Override
    public void close() {
        scope.close();
        synchronized (this) {
            repositories.clear();
            storageDefinitions.clear();
        }
        if (closed.compareAndSet(false, true)) {
            closeOwnedResources.run();
        }
    }

    /** 仓库缓存项，保留定义用于重复绑定校验。 */
    private record Entry(RepositoryDefinition<?, ?> definition, CrudRepository<?, ?> repository) { }
    /** namespace 与 collection 组成的物理存储身份。 */
    private record StorageKey(String namespace, String collection) { }
}
