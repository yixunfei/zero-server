package group.zn.zero.data.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.ZeroDataEnvelopeCrudRepository;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.mapping.ZeroDataField;
import group.zn.zero.data.mapping.ZeroDataId;
import group.zn.zero.data.mapping.ZeroDataMappingIntrospector;
import group.zn.zero.data.mapping.ZeroDataObject;
import group.zn.zero.data.mapping.ZeroDataVersion;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * PostgreSQL driver 外部集成测试。
 *
 * @author zn
 */
class PostgresqlDataAdapterExternalIT {

    /**
     * 验证 PostgreSQL JDBC-backed Repository 可完成真实 CRUD。
     */
    @Test
    void repositoryShouldUseRealPostgresqlDriver() {
        assumeTrue(PostgresqlDriverSettings.hasRequiredSettings(), "PostgreSQL external settings are unavailable");
        PostgresqlDriverSettings settings = PostgresqlDriverSettings.fromSystemProperties();
        PostgresqlDataHealthCheck healthCheck = new PostgresqlDataHealthCheck(settings);
        assumeTrue(healthCheck.check(), "PostgreSQL external service is unavailable");
        PostgresqlDataAdapter adapter = new PostgresqlDataAdapter();
        ZeroDataEnvelopeCrudRepository<String, PlayerArchive> repository = adapter.registerDriverRepository(
                "player_external",
                settings,
                new ZeroDataMappingIntrospector().inspect(PlayerArchive.class),
                new PlayerArchiveCodec(),
                1);
        repository.deleteById("postgresql-player-1").toCompletableFuture().join();

        repository.save(new PlayerArchive("postgresql-player-1", 0L, "created")).toCompletableFuture().join();
        PlayerArchive saved = repository.findById("postgresql-player-1").toCompletableFuture().join().orElseThrow();
        repository.save(new PlayerArchive(saved.id(), saved.version(), "updated")).toCompletableFuture().join();
        PlayerArchive updated = repository.findById("postgresql-player-1").toCompletableFuture().join().orElseThrow();
        CompletionException exception = assertThrows(CompletionException.class, () ->
                repository.save(new PlayerArchive("postgresql-player-1", saved.version(), "stale"))
                        .toCompletableFuture()
                        .join());
        repository.deleteById("postgresql-player-1").toCompletableFuture().join();

        assertEquals(1L, saved.version());
        assertEquals(2L, updated.version());
        assertEquals("updated", updated.name());
        assertEquals(DataErrorCode.VERSION_CONFLICT, ((ZeroException) exception.getCause()).errorCode());
        assertEquals(0L, repository.count().toCompletableFuture().join());
    }

    /**
     * 玩家归档对象。
     *
     * @param id 玩家 ID。
     * @param version 版本号。
     * @param name 名称。
     * @author zn
     */
    @ZeroDataObject(namespace = "game", collection = "player_external", schemaVersion = 1)
    private record PlayerArchive(
            @ZeroDataId String id,
            @ZeroDataVersion long version,
            @ZeroDataField(order = 1, name = "name") String name) implements VersionedEntity<String> {

        /**
         * 返回指定版本的新实体。
         *
         * @param version 新版本号。
         * @return 新实体；不可为空。
         */
        @Override
        public PlayerArchive withVersion(final long version) {
            return new PlayerArchive(id, version, name);
        }
    }

    /**
     * 玩家归档 payload codec。
     *
     * @author zn
     */
    private static final class PlayerArchiveCodec implements ZeroPayloadCodec<PlayerArchive> {

        /**
         * 创建玩家归档 payload codec。
         */
        private PlayerArchiveCodec() {
        }

        /**
         * 返回提供者名称。
         *
         * @return 提供者名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "postgresql-player-archive-codec";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<PlayerArchive> messageType() {
            return PlayerArchive.class;
        }

        /**
         * 写入 payload。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final PlayerArchive message) {
            writer.writeString(message.id());
            writer.writeLong(message.version());
            writer.writeString(message.name());
        }

        /**
         * 读取 payload。
         *
         * @param reader 读取器；不可为空。
         * @return 消息；不可为空；线程安全。
         */
        @Override
        public PlayerArchive read(final ZeroReader reader) {
            return new PlayerArchive(reader.readString(), reader.readLong(), reader.readString());
        }
    }
}
