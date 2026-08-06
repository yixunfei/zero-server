package group.zn.zero.data.envelope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.mapping.ZeroDataCompositeKey;
import group.zn.zero.data.mapping.ZeroDataField;
import group.zn.zero.data.mapping.ZeroDataId;
import group.zn.zero.data.mapping.ZeroDataKeyCodec;
import group.zn.zero.data.mapping.ZeroDataKeyGenerator;
import group.zn.zero.data.mapping.ZeroDataKeyPart;
import group.zn.zero.data.mapping.ZeroDataMappingIntrospector;
import group.zn.zero.data.mapping.ZeroDataObject;
import group.zn.zero.data.mapping.ZeroDataObjectMetadata;
import group.zn.zero.data.mapping.ZeroDataVersion;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * 数据对象信封 CRUD Repository 测试。
 *
 * @author zn
 */
class ZeroDataEnvelopeCrudRepositoryTest {

    /**
     * 验证 Repository 使用 zcode payload 保存对象并保持版本语义。
     */
    @Test
    void repositoryShouldSaveWithEnvelopeAndRejectStaleWrite() {
        ZeroDataEnvelopeCrudRepository<String, PlayerArchive> repository = newRepository(new TestEnvelopeStore());

        repository.save(new PlayerArchive("p1", 0L, "created")).toCompletableFuture().join();
        PlayerArchive saved = repository.findById("p1").toCompletableFuture().join().orElseThrow();
        repository.save(new PlayerArchive(saved.id(), saved.version(), "updated")).toCompletableFuture().join();
        PlayerArchive updated = repository.findById("p1").toCompletableFuture().join().orElseThrow();

        CompletionException exception = assertThrows(CompletionException.class, () ->
                repository.save(new PlayerArchive("p1", saved.version(), "stale")).toCompletableFuture().join());

        assertEquals(1L, saved.version());
        assertEquals(2L, updated.version());
        assertEquals("updated", updated.name());
        assertEquals(DataErrorCode.VERSION_CONFLICT, ((ZeroException) exception.getCause()).errorCode());
    }

    /**
     * 验证信封 ID 与 payload ID 不一致时会拒绝解码。
     */
    @Test
    void entityCodecShouldRejectEnvelopePayloadMismatch() {
        ZeroDataEntityCodec<String, PlayerArchive> codec = newEntityCodec();
        ZeroDataEnvelope envelope = codec.encode(new PlayerArchive("p1", 1L, "value"));
        ZeroDataEnvelope mismatch = new ZeroDataEnvelope(
                envelope.namespace(),
                envelope.collection(),
                "str:bWlzbWF0Y2g",
                envelope.version(),
                envelope.schemaVersion(),
                envelope.codecVersion(),
                envelope.encodedAtEpochMillis(),
                envelope.payload());

        ZeroException exception = assertThrows(ZeroException.class, () -> codec.decode(mismatch));

        assertEquals(DataErrorCode.READ_FAILED, exception.errorCode());
    }

    /**
     * 验证组合键可以从 `@ZeroDataKeyPart` 字段派生存储键。
     */
    @Test
    void repositoryShouldSaveCompositeKeyEntity() {
        ZeroDataObjectMetadata metadata = new ZeroDataMappingIntrospector().inspect(PlayerItemArchive.class);
        ZeroDataEnvelopeCrudRepository<PlayerItemKey, PlayerItemArchive> repository =
                new ZeroDataEnvelopeCrudRepository<>(
                        new ZeroDataEntityCodec<>(metadata, new PlayerItemArchiveCodec(), 1),
                        new TestEnvelopeStore());

        repository.save(new PlayerItemArchive("p1", 7L, 0L, "bag")).toCompletableFuture().join();
        PlayerItemArchive saved = repository.findById(new PlayerItemKey("p1", 7L))
                .toCompletableFuture()
                .join()
                .orElseThrow();

        assertEquals(1L, saved.version());
        assertEquals("bag", saved.slot());
    }

    /**
     * 验证配置 ID 生成器的实体可以在保存时回填主键并写入 payload。
     */
    @Test
    void repositoryShouldGenerateMissingUuidStyleId() {
        ZeroDataObjectMetadata metadata = new ZeroDataMappingIntrospector().inspect(GeneratedArchive.class);
        ZeroDataEnvelopeCrudRepository<String, GeneratedArchive> repository = new ZeroDataEnvelopeCrudRepository<>(
                new ZeroDataEntityCodec<>(metadata, new GeneratedArchiveCodec(), 1),
                new TestEnvelopeStore());

        repository.save(new GeneratedArchive(null, 0L, "created")).toCompletableFuture().join();
        GeneratedArchive saved = repository.findById("generated-id").toCompletableFuture().join().orElseThrow();

        assertEquals("generated-id", saved.id());
        assertEquals(1L, saved.version());
        assertEquals("created", saved.name());
    }

    private ZeroDataEnvelopeCrudRepository<String, PlayerArchive> newRepository(final ZeroDataEnvelopeStore store) {
        return new ZeroDataEnvelopeCrudRepository<>(newEntityCodec(), store);
    }

    private ZeroDataEntityCodec<String, PlayerArchive> newEntityCodec() {
        ZeroDataObjectMetadata metadata = new ZeroDataMappingIntrospector().inspect(PlayerArchive.class);
        return new ZeroDataEntityCodec<>(metadata, new PlayerArchiveCodec(), 1);
    }

    /**
     * 玩家归档对象。
     *
     * @param id 玩家 ID。
     * @param version 版本号。
     * @param name 名称。
     * @author zn
     */
    @ZeroDataObject(namespace = "game", collection = "player", schemaVersion = 1)
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
     * 玩家物品组合键。
     *
     * @param playerId 玩家 ID。
     * @param itemId 物品 ID。
     * @author zn
     */
    private record PlayerItemKey(String playerId, long itemId) {
    }

    /**
     * 玩家物品归档对象。
     *
     * @param playerId 玩家 ID。
     * @param itemId 物品 ID。
     * @param version 版本号。
     * @param slot 槽位。
     * @author zn
     */
    @ZeroDataObject(namespace = "game", collection = "player_item", schemaVersion = 1)
    @ZeroDataCompositeKey(codec = PlayerItemKeyCodec.class)
    private record PlayerItemArchive(
            @ZeroDataKeyPart(order = 1, name = "playerId") String playerId,
            @ZeroDataKeyPart(order = 2, name = "itemId") long itemId,
            @ZeroDataVersion long version,
            @ZeroDataField(order = 1, name = "slot") String slot) implements VersionedEntity<PlayerItemKey> {

        /**
         * 返回组合键。
         *
         * @return 这里故意返回空，由映射元数据从 key part 派生；线程安全。
         */
        @Override
        public PlayerItemKey id() {
            return null;
        }

        /**
         * 返回指定版本的新实体。
         *
         * @param version 新版本号。
         * @return 新实体；不可为空。
         */
        @Override
        public PlayerItemArchive withVersion(final long version) {
            return new PlayerItemArchive(playerId, itemId, version, slot);
        }
    }

    /**
     * 生成 ID 的归档对象。
     *
     * @param id 对象 ID。
     * @param version 版本号。
     * @param name 名称。
     * @author zn
     */
    @ZeroDataObject(namespace = "game", collection = "generated", schemaVersion = 1)
    private record GeneratedArchive(
            @ZeroDataId(generator = FixedZeroDataKeyGenerator.class) String id,
            @ZeroDataVersion long version,
            @ZeroDataField(order = 1, name = "name") String name) implements VersionedEntity<String> {

        /**
         * 返回带新主键的实体。
         *
         * @param id 新主键；不可为空。
         * @return 新实体；不可为空。
         */
        @Override
        public GeneratedArchive withId(final String id) {
            return new GeneratedArchive(id, version, name);
        }

        /**
         * 返回指定版本的新实体。
         *
         * @param version 新版本号。
         * @return 新实体；不可为空。
         */
        @Override
        public GeneratedArchive withVersion(final long version) {
            return new GeneratedArchive(id, version, name);
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
            return "test-player-archive-codec";
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

    /**
     * 玩家物品组合键 codec。
     *
     * @author zn
     */
    private static final class PlayerItemKeyCodec implements ZeroDataKeyCodec<PlayerItemKey> {

        /**
         * 创建玩家物品组合键 codec。
         */
        private PlayerItemKeyCodec() {
        }

        /**
         * 编码组合键。
         *
         * @param key 组合键；不可为空。
         * @return 编码值；不可为空；线程安全。
         */
        @Override
        public String encode(final PlayerItemKey key) {
            return key.playerId() + "#" + key.itemId();
        }

        /**
         * 解码组合键。
         *
         * @param value 编码值；不可为空。
         * @return 组合键；不可为空；线程安全。
         */
        @Override
        public PlayerItemKey decode(final String value) {
            String[] parts = value.split("#", 2);
            return new PlayerItemKey(parts[0], Long.parseLong(parts[1]));
        }
    }

    /**
     * 固定 ID 生成器。
     *
     * @author zn
     */
    private static final class FixedZeroDataKeyGenerator implements ZeroDataKeyGenerator<String> {

        /**
         * 创建固定 ID 生成器。
         */
        private FixedZeroDataKeyGenerator() {
        }

        /**
         * 生成固定 ID。
         *
         * @return 固定 ID；不可为空；线程安全。
         */
        @Override
        public String generate() {
            return "generated-id";
        }
    }

    /**
     * 玩家物品 payload codec。
     *
     * @author zn
     */
    private static final class PlayerItemArchiveCodec implements ZeroPayloadCodec<PlayerItemArchive> {

        /**
         * 创建玩家物品 payload codec。
         */
        private PlayerItemArchiveCodec() {
        }

        /**
         * 返回提供者名称。
         *
         * @return 提供者名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "test-player-item-archive-codec";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<PlayerItemArchive> messageType() {
            return PlayerItemArchive.class;
        }

        /**
         * 写入 payload。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final PlayerItemArchive message) {
            writer.writeString(message.playerId());
            writer.writeLong(message.itemId());
            writer.writeLong(message.version());
            writer.writeString(message.slot());
        }

        /**
         * 读取 payload。
         *
         * @param reader 读取器；不可为空。
         * @return 消息；不可为空；线程安全。
         */
        @Override
        public PlayerItemArchive read(final ZeroReader reader) {
            return new PlayerItemArchive(
                    reader.readString(),
                    reader.readLong(),
                    reader.readLong(),
                    reader.readString());
        }
    }

    /**
     * 生成 ID 归档 payload codec。
     *
     * @author zn
     */
    private static final class GeneratedArchiveCodec implements ZeroPayloadCodec<GeneratedArchive> {

        /**
         * 创建生成 ID 归档 payload codec。
         */
        private GeneratedArchiveCodec() {
        }

        /**
         * 返回提供者名称。
         *
         * @return 提供者名称；不可为空；线程安全。
         */
        @Override
        public String name() {
            return "test-generated-archive-codec";
        }

        /**
         * 返回消息类型。
         *
         * @return 消息类型；不可为空；线程安全。
         */
        @Override
        public Class<GeneratedArchive> messageType() {
            return GeneratedArchive.class;
        }

        /**
         * 写入 payload。
         *
         * @param writer 写入器；不可为空。
         * @param message 消息；不可为空。
         */
        @Override
        public void write(final ZeroWriter writer, final GeneratedArchive message) {
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
        public GeneratedArchive read(final ZeroReader reader) {
            return new GeneratedArchive(reader.readString(), reader.readLong(), reader.readString());
        }
    }

    /**
     * 测试用信封存储。
     *
     * @author zn
     */
    private static final class TestEnvelopeStore implements ZeroDataEnvelopeStore {

        /**
         * 信封 map。
         */
        private final Map<String, ZeroDataEnvelope> envelopes = new LinkedHashMap<>();

        /**
         * 根据编码 ID 查询信封。
         *
         * @param id 编码后的存储 ID；不可为空。
         * @return 查询结果；不可为空；可能为空；线程安全。
         */
        @Override
        public Optional<ZeroDataEnvelope> findById(final String id) {
            return Optional.ofNullable(envelopes.get(Objects.requireNonNull(id, "id")));
        }

        /**
         * 查询全部信封。
         *
         * @return 信封列表；不可为空；可能为空；有序；线程安全。
         */
        @Override
        public List<ZeroDataEnvelope> findAll() {
            return List.copyOf(envelopes.values());
        }

        /**
         * 保存信封。
         *
         * @param envelope 信封；不可为空。
         */
        @Override
        public void save(final ZeroDataEnvelope envelope) {
            envelopes.put(envelope.id(), envelope);
        }

        /**
         * 根据编码 ID 删除信封。
         *
         * @param id 编码后的存储 ID；不可为空。
         */
        @Override
        public void deleteById(final String id) {
            envelopes.remove(Objects.requireNonNull(id, "id"));
        }

        /**
         * 统计信封数量。
         *
         * @return 信封数量；线程安全。
         */
        @Override
        public long count() {
            return envelopes.size();
        }
    }
}
