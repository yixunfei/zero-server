package group.zn.zero.data.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.ZeroDataEnvelopeCrudRepository;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.mapping.DefaultZeroDataKeyCodec;
import group.zn.zero.data.mapping.ZeroDataField;
import group.zn.zero.data.mapping.ZeroDataId;
import group.zn.zero.data.mapping.ZeroDataMappingIntrospector;
import group.zn.zero.data.mapping.ZeroDataObject;
import group.zn.zero.data.mapping.ZeroDataVersion;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.RedisClient;

/**
 * Redis driver 外部集成测试。
 *
 * @author zn
 */
class RedisDataAdapterExternalIT {

    /**
     * 临时目录。
     */
    @TempDir
    private Path tempDir;

    /**
     * 验证 Redis driver-backed Repository 可完成真实 CRUD 并追加 Redis journal。
     */
    @Test
    void repositoryShouldUseRealRedisDriverAndRedisJournal() {
        RedisDriverSettings settings = RedisDriverSettings.fromSystemProperties();
        DefaultRedisDataKeyStrategy keyStrategy = new DefaultRedisDataKeyStrategy(16);
        RedisDataAdapter adapter = new RedisDataAdapter(keyStrategy);
        try (RedisClient client = adapter.createClient(settings)) {
            RedisDataHealthCheck healthCheck = new RedisDataHealthCheck(client);
            assertTrue(healthCheck.check(), "Redis external service is unavailable");
            client.flushDB();
            LocalDiskDataJournal localJournal = new LocalDiskDataJournal(tempDir);
            ZeroDataEnvelopeCrudRepository<String, PlayerArchive> repository = adapter.registerDriverRepository(
                    "player_external",
                    client,
                    new ZeroDataMappingIntrospector().inspect(PlayerArchive.class),
                    new PlayerArchiveCodec(),
                    1,
                    localJournal);

            repository.save(new PlayerArchive("redis-player-1", 0L, "created")).toCompletableFuture().join();
            PlayerArchive saved = repository.findById("redis-player-1").toCompletableFuture().join().orElseThrow();
            repository.save(new PlayerArchive(saved.id(), saved.version(), "updated")).toCompletableFuture().join();
            PlayerArchive updated = repository.findById("redis-player-1").toCompletableFuture().join().orElseThrow();
            CompletionException exception = assertThrows(CompletionException.class, () ->
                    repository.save(new PlayerArchive("redis-player-1", saved.version(), "stale"))
                            .toCompletableFuture()
                            .join());
            repository.deleteById("redis-player-1").toCompletableFuture().join();

            assertEquals(1L, saved.version());
            assertEquals(2L, updated.version());
            assertEquals("updated", updated.name());
            assertEquals(DataErrorCode.VERSION_CONFLICT, ((ZeroException) exception.getCause()).errorCode());
            assertEquals(0L, repository.count().toCompletableFuture().join());
            String encodedId = new DefaultZeroDataKeyCodec().encode("redis-player-1");
            assertEquals(3L, client.llen(keyStrategy.journalKey("game", "player_external", encodedId)));
            assertEquals(0, localJournal.readAll("game", "player_external").size());
        }
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
            return "redis-player-archive-codec";
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
