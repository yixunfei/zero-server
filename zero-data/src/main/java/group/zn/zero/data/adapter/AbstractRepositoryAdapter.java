package group.zn.zero.data.adapter;

import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.data.DataService;
import group.zn.zero.data.repository.CrudRepository;
import group.zn.zero.data.model.VersionedEntity;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 数据仓库适配器基类。
 *
 * <p>负责管理命名仓库、健康状态和本地开发可用性。
 *
 * @author zn
 */
public abstract class AbstractRepositoryAdapter extends AbstractLifecycle implements DataService {

    /**
     * 仓库名称。
     */
    private final String serviceName;

    /**
     * 仓库注册表。
     */
    private final ConcurrentMap<String, CrudRepository<?, ?>> repositories = new ConcurrentHashMap<>();

    /**
     * 健康状态。
     */
    private volatile boolean healthy = true;

    /**
     * 创建仓库适配器基类。
     *
     * @param serviceName 服务名称；不可为空。
     * @throws NullPointerException 当服务名称为空时抛出。
     */
    protected AbstractRepositoryAdapter(final String serviceName) {
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
    }

    /**
     * 返回数据服务名称。
     *
     * @return 服务名称；不可为空；线程安全。
     */
    @Override
    public String serviceName() {
        return serviceName;
    }

    /**
     * 返回健康状态。
     *
     * @return true 表示健康；线程安全。
     */
    public boolean healthy() {
        return healthy;
    }

    /**
     * 设置健康状态。
     *
     * @param healthy 是否健康。
     */
    public void healthy(final boolean healthy) {
        this.healthy = healthy;
    }

    /**
     * 注册命名仓库。
     *
     * @param name 仓库名称；不可为空。
     * @param repository 仓库；不可为空。
     * @param <ID> 主键类型。
     * @param <T> 实体类型。
     */
    public <ID, T extends VersionedEntity<ID>> void registerRepository(
            final String name,
            final CrudRepository<ID, T> repository) {
        repositories.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(repository, "repository"));
    }

    /**
     * 获取命名仓库。
     *
     * @param name 仓库名称；不可为空。
     * @param <ID> 主键类型。
     * @param <T> 实体类型。
     * @return 命名仓库；不可为空；可能为空；线程安全。
     */
    @SuppressWarnings("unchecked")
    public <ID, T extends VersionedEntity<ID>> Optional<CrudRepository<ID, T>> repository(final String name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable((CrudRepository<ID, T>) repositories.get(name));
    }

    /**
     * 返回仓库名称列表。
     *
     * @return 不可变、有序、可能为空、线程安全的仓库名称列表。
     */
    public List<String> repositoryNames() {
        return List.copyOf(repositories.keySet().stream().sorted().toList());
    }

    /**
     * 启动时默认标记为健康。
     */
    @Override
    protected void doStart() {
        healthy = true;
    }

    /**
     * 停止时标记为不健康。
     */
    @Override
    protected void doStop() {
        healthy = false;
    }
}
