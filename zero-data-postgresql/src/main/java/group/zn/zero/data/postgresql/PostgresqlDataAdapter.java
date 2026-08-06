package group.zn.zero.data.postgresql;

import group.zn.zero.data.adapter.AbstractRepositoryAdapter;
import group.zn.zero.data.envelope.ZeroDataEntityCodec;
import group.zn.zero.data.envelope.ZeroDataEnvelopeCrudRepository;
import group.zn.zero.data.mapping.ZeroDataObjectMetadata;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import java.util.Objects;

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
     * 注册基于 zcode 信封的 PostgreSQL Repository。
     *
     * @param name 仓库名称；不可为空。
     * @param metadata 映射元数据；不可为空。
     * @param payloadCodec payload codec；不可为空。
     * @param codecVersion payload codec 版本；必须大于 0。
     * @param <ID> 主键类型。
     * @param <T> 实体类型。
     * @return 已注册仓库；不可为空；线程安全。
     */
    public <ID, T extends VersionedEntity<ID>> ZeroDataEnvelopeCrudRepository<ID, T> registerZcodeRepository(
            final String name,
            final ZeroDataObjectMetadata metadata,
            final ZeroPayloadCodec<T> payloadCodec,
            final int codecVersion) {
        ZeroDataEntityCodec<ID, T> entityCodec = new ZeroDataEntityCodec<>(
                Objects.requireNonNull(metadata, "metadata"),
                Objects.requireNonNull(payloadCodec, "payloadCodec"),
                codecVersion);
        ZeroDataEnvelopeCrudRepository<ID, T> repository =
                new ZeroDataEnvelopeCrudRepository<>(entityCodec, new PostgresqlDataEnvelopeStore());
        registerRepository(name, repository);
        return repository;
    }

    /**
     * 注册基于 PostgreSQL JDBC driver 的 zcode Repository。
     *
     * @param name 仓库名称；不可为空。
     * @param settings PostgreSQL driver 配置；不可为空。
     * @param metadata 映射元数据；不可为空。
     * @param payloadCodec payload codec；不可为空。
     * @param codecVersion payload codec 版本；必须大于 0。
     * @param <ID> 主键类型。
     * @param <T> 实体类型。
     * @return 已注册仓库；不可为空；线程安全。
     */
    public <ID, T extends VersionedEntity<ID>> ZeroDataEnvelopeCrudRepository<ID, T> registerDriverRepository(
            final String name,
            final PostgresqlDriverSettings settings,
            final ZeroDataObjectMetadata metadata,
            final ZeroPayloadCodec<T> payloadCodec,
            final int codecVersion) {
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
                        Objects.requireNonNull(settings, "settings")));
        registerRepository(name, repository);
        return repository;
    }
}
