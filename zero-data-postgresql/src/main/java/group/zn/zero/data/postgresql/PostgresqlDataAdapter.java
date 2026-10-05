package group.zn.zero.data.postgresql;

import group.zn.zero.data.adapter.AbstractRepositoryAdapter;
import group.zn.zero.data.envelope.ZeroDataEntityCodec;
import group.zn.zero.data.envelope.EnvelopeRepositoryFactory;
import group.zn.zero.data.envelope.ZeroDataEnvelopeCrudRepository;
import group.zn.zero.data.mapping.ZeroDataObjectMetadata;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.sql.DataSource;

/**
 * PostgreSQL 数据适配器。
 *
 * @author zn
 */
public final class PostgresqlDataAdapter extends AbstractRepositoryAdapter {

    /**
     * 创建 PostgreSQL 数据适配器。
     */
    public PostgresqlDataAdapter() {
        super("postgresql");
    }

    /**
     * 创建共享有界连接池的线程安全工厂；close 等待在途 IO 并关闭池。
     *
     * @param settings 连接配置；不可为空；默认最大连接数 8，借用超时 3 秒。
     * @param ioExecutor 受管 IO 执行器；不可为空；调用方管理生命周期，生产不可内联执行。
     * @return 工厂；不可为空；调用方负责关闭。
     * @throws NullPointerException 参数为空时抛出。
     */
    public EnvelopeRepositoryFactory repositoryFactory(final PostgresqlDriverSettings settings, final Executor ioExecutor) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(ioExecutor, "ioExecutor");
        var pool = PostgresqlPool.create(settings, 8, java.time.Duration.ofSeconds(3));
        return new EnvelopeRepositoryFactory(metadata -> new PostgresqlDriverEnvelopeStore(
                metadata.namespace(), metadata.collection(), settings.tableName(), pool), ioExecutor, pool::close);
    }

    /**
     * 创建使用外部 DataSource 的线程安全工厂，不取得 DataSource/执行器所有权。
     *
     * @param source 调用方管理的连接池；连接必须开启 auto-commit。
     * @param tableName 合法 SQL 表名；不可为空。
     * @param ioExecutor 受管 IO 执行器；生产不可内联执行。
     * @return 工厂；不可为空；close 等待在途 IO，但不关闭外部资源。
     * @throws IllegalArgumentException 表名非法时抛出。
     * @throws NullPointerException 必要参数为空时抛出。
     */
    public EnvelopeRepositoryFactory repositoryFactory(
            final DataSource source, final String tableName, final Executor ioExecutor) {
        Objects.requireNonNull(source, "source");
        String table = PostgresqlDriverSettings.requireIdentifier(tableName);
        return new EnvelopeRepositoryFactory(metadata -> new PostgresqlDriverEnvelopeStore(
                metadata.namespace(), metadata.collection(), table, source), ioExecutor);
    }

    /**
     * 注册基于 zcode 信封的 PostgreSQL Repository。
     *
     * @param name 仓库名称；不可为空。
     * @param metadata 映射元数据；不可为空。
     * @param payloadCodec payload codec；不可为空。
     * @param codecVersion payload codec 版本；必须大于 0。
     * @param ioExecutor 受管 IO 执行器；不可为空；调用方管理生命周期。
     * @param <ID> 主键类型。
     * @param <T> 实体类型。
     * @return 已注册仓库；不可为空；线程安全。
     */
    public <ID, T extends VersionedEntity<ID>> ZeroDataEnvelopeCrudRepository<ID, T> registerZcodeRepository(
            final String name,
            final ZeroDataObjectMetadata metadata,
            final ZeroPayloadCodec<T> payloadCodec,
            final int codecVersion, final Executor ioExecutor) {
        ZeroDataEntityCodec<ID, T> entityCodec = new ZeroDataEntityCodec<>(
                Objects.requireNonNull(metadata, "metadata"),
                Objects.requireNonNull(payloadCodec, "payloadCodec"),
                codecVersion);
        ZeroDataEnvelopeCrudRepository<ID, T> repository =
                new ZeroDataEnvelopeCrudRepository<>(entityCodec, new PostgresqlDataEnvelopeStore(), ioExecutor);
        registerRepository(name, repository);
        return repository;
    }

    /**
     * 注册基于 PostgreSQL JDBC driver 的 zcode Repository。
     *
     * @param name 仓库名称；不可为空。
     * @param source 外部连接池；不可为空；调用方管理生命周期，连接必须开启 auto-commit。
     * @param tableName 合法 SQL 表名；不可为空。
     * @param metadata 映射元数据；不可为空。
     * @param payloadCodec payload codec；不可为空。
     * @param codecVersion payload codec 版本；必须大于 0。
     * @param ioExecutor 受管 IO 执行器；不可为空；生产不可内联执行，调用方管理生命周期。
     * @param <ID> 主键类型。
     * @param <T> 实体类型。
     * @return 已注册仓库；不可为空；线程安全。
     */
    public <ID, T extends VersionedEntity<ID>> ZeroDataEnvelopeCrudRepository<ID, T> registerDriverRepository(
            final String name,
            final DataSource source,
            final String tableName,
            final ZeroDataObjectMetadata metadata,
            final ZeroPayloadCodec<T> payloadCodec,
            final int codecVersion, final Executor ioExecutor) {
        ZeroDataObjectMetadata currentMetadata = Objects.requireNonNull(metadata, "metadata");
        ZeroDataEntityCodec<ID, T> entityCodec = new ZeroDataEntityCodec<>(
                currentMetadata,
                Objects.requireNonNull(payloadCodec, "payloadCodec"),
                codecVersion);
        ZeroDataEnvelopeCrudRepository<ID, T> repository = new ZeroDataEnvelopeCrudRepository<>(
                entityCodec,
                new PostgresqlDriverEnvelopeStore(
                        currentMetadata.namespace(),
                        currentMetadata.collection(),
                        tableName, Objects.requireNonNull(source, "source")), ioExecutor);
        registerRepository(name, repository);
        return repository;
    }
}
