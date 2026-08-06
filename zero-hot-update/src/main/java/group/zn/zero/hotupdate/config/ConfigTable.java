package group.zn.zero.hotupdate.config;

import group.zn.zero.core.error.ZeroException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * typed 配置表读取句柄。
 *
 * <p>业务线程只通过该句柄读取当前不可变快照。文件读取、decode、校验和发布由
 * `LocalConfigHotReloadService` 负责，不会进入读取路径。
 *
 * @param <K> 配置 key 类型。
 * @param <V> 配置对象类型。
 * @author zn
 */
public final class ConfigTable<K, V> {

    /**
     * 不可变表定义。
     */
    private final ConfigTableDefinition<K, V> definition;

    /**
     * 当前已发布快照。
     */
    private final AtomicReference<ConfigTableSnapshot<K, V>> current = new AtomicReference<>();

    ConfigTable(final ConfigTableDefinition<K, V> definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
    }

    /**
     * 返回配置表定义。
     *
     * @return 不可变定义；不可为空；线程安全。
     */
    public ConfigTableDefinition<K, V> definition() {
        return definition;
    }

    /**
     * 返回配置表是否已有已发布快照。
     *
     * @return 已加载时返回 true；线程安全。
     */
    public boolean loaded() {
        return current.get() != null;
    }

    /**
     * 返回当前不可变快照。
     *
     * @return 当前快照；不可为空、线程安全。
     * @throws ZeroException 当配置服务尚未完成初始加载时抛出。
     */
    public ConfigTableSnapshot<K, V> snapshot() {
        ConfigTableSnapshot<K, V> snapshot = current.get();
        if (snapshot == null) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.TABLE_NOT_LOADED,
                    "config table is not loaded: " + definition.tableName(),
                    null);
        }
        return snapshot;
    }

    /**
     * 从当前快照按 key 查找配置对象。
     *
     * @param key 配置 key；不可为空。
     * @return 配置对象；为空表示当前版本不存在；线程安全。
     * @throws ZeroException 当配置表尚未完成初始加载时抛出。
     */
    public Optional<V> find(final K key) {
        return snapshot().find(Objects.requireNonNull(key, "key"));
    }

    synchronized ConfigReloadCandidate prepareInitial(final Instant loadedAt) {
        if (current.get() != null) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.INVALID_SERVICE_STATE,
                    "config table already has an initial snapshot: " + definition.tableName(),
                    null);
        }
        ConfigTableSnapshot<K, V> candidate = CsvConfigTableLoader.load(definition, 1, loadedAt);
        return new ConfigReloadCandidate(
                definition.tableName(),
                sourceName(),
                candidate.version(),
                candidate.checksum(),
                candidate.rowCount(),
                () -> publishInitial(candidate),
                () -> rollBackInitial(candidate));
    }

    synchronized ConfigTableReloadOutcome reload(final Instant loadedAt) {
        ConfigTableSnapshot<K, V> previous = snapshot();
        ConfigTableSnapshot<K, V> candidate = CsvConfigTableLoader.load(
                definition,
                previous.version() + 1,
                loadedAt);
        if (previous.checksum().equals(candidate.checksum())) {
            return new ConfigTableReloadOutcome(
                    ConfigReloadStatus.NO_CHANGE,
                    previous.version(),
                    previous.version(),
                    previous.checksum(),
                    previous.rowCount());
        }
        current.set(candidate);
        return new ConfigTableReloadOutcome(
                ConfigReloadStatus.LOADED,
                previous.version(),
                candidate.version(),
                candidate.checksum(),
                candidate.rowCount());
    }

    ConfigTableSnapshot<K, V> currentOrNull() {
        return current.get();
    }

    long nextVersion() {
        ConfigTableSnapshot<K, V> snapshot = current.get();
        return snapshot == null ? 1 : snapshot.version() + 1;
    }

    String sourceName() {
        return definition.source().getFileName().toString();
    }

    private synchronized void publishInitial(final ConfigTableSnapshot<K, V> candidate) {
        if (!current.compareAndSet(null, candidate)) {
            throw ZeroException.of(
                    ConfigReloadErrorCode.INVALID_SERVICE_STATE,
                    "initial config snapshot was published concurrently: " + definition.tableName(),
                    null);
        }
    }

    private synchronized void rollBackInitial(final ConfigTableSnapshot<K, V> candidate) {
        current.compareAndSet(candidate, null);
    }
}
