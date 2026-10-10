package group.zn.zero.data.envelope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import group.zn.zero.data.model.PageRequest;
import group.zn.zero.data.model.PageResult;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import org.junit.jupiter.api.Test;

/**
 * 数据对象信封 CRUD Repository 测试。
 *
 * @author zn
 */
class ZeroDataEnvelopeCrudRepositoryTest {

    @Test void unresolvedCompositeKeyIsMappingErrorInsteadOfStringFallback() {
        var metadata = new ZeroDataMappingIntrospector().inspect(PlayerItemArchive.class);
        var codec = new ZeroDataEntityCodec<PlayerItemKey, PlayerItemArchive>(metadata, new PlayerItemArchiveCodec(),
                new UnresolvedKeyCodec<>(), 1, java.time.Clock.systemUTC());
        var failure = assertThrows(ZeroException.class, () -> codec.idOf(new PlayerItemArchive("p", 1, 1, "bag")));
        assertEquals(DataErrorCode.MAPPING_INVALID, failure.errorCode());
    }

    /** 运行时无法恢复 K 的实际类型，不得据此猜测 String。 @author zn */
    private static final class UnresolvedKeyCodec<K> implements ZeroDataKeyCodec<K> {
        @Override public String encode(final K key) { return key.toString(); }
        @Override public K decode(final String value) { throw new UnsupportedOperationException(); }
    }

    /** 阻塞驱动不能占用调用线程，也不能让同一 Repository 的两个读取相互串行。 */
    @Test
    void blockedReadsRunConcurrentlyInInjectedIoExecutor() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        ZeroDataEnvelopeStore store = proxyStore((proxy, method, args) -> {
            assertEquals("findById", method.getName());
            assertFalse(Thread.currentThread() == caller);
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return Optional.empty();
        });
        try (var io = Executors.newFixedThreadPool(2)) {
            var repository = new ZeroDataEnvelopeCrudRepository<>(newEntityCodec(), store, io);
            var first = repository.findById("p1").toCompletableFuture();
            var second = repository.findById("p2").toCompletableFuture();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(first.isDone());
                assertFalse(second.isDone());
            } finally {
                release.countDown();
            }
            assertTrue(first.get(5, TimeUnit.SECONDS).isEmpty());
            assertTrue(second.get(5, TimeUnit.SECONDS).isEmpty());
        }
    }

    /** 批量/分页必须调用 Store 扩展，不得退化为单条查询或全量读取。 */
    @Test
    void batchAndPageOperationsArePushedDownAndInputsAreSnapshotted() {
        ArrayDeque<Runnable> pending = new ArrayDeque<>();
        ZeroDataEntityCodec<String, PlayerArchive> codec = newEntityCodec();
        var first = codec.encode(new PlayerArchive("p1", 1, "one"));
        var second = codec.encode(new PlayerArchive("p2", 1, "two"));
        List<String> ids = new ArrayList<>(List.of("p2", "missing", "p1", "p2"));
        var store = proxyStore((proxy, method, args) -> switch (method.getName()) {
            case "findByIds" -> {
                assertEquals(List.of(codec.encodeId("p2"), codec.encodeId("missing"),
                        codec.encodeId("p1"), codec.encodeId("p2")), args[0]);
                yield List.of(first, second);
            }
            case "findPage" -> new PageResult<>(List.of(second), "2", 999);
            case "saveIfVersionBatch" -> {
                var writes = (List<?>) args[0];
                assertEquals(2, writes.size());
                assertEquals(first.id(), ((ZeroDataEnvelopeStore.ConditionalWrite) writes.getFirst()).envelope().id());
                yield List.of(true, true);
            }
            case "deleteAll" -> {
                assertEquals(List.of(codec.encodeId("p1"), codec.encodeId("p2")), args[0]);
                yield null;
            }
            default -> throw new AssertionError("unexpected fallback: " + method.getName());
        });
        var repository = new ZeroDataEnvelopeCrudRepository<>(codec, store, pending::add);
        var found = repository.findByIds(ids).toCompletableFuture();
        ids.clear();
        pending.remove().run();
        assertEquals(List.of("p2", "p1", "p2"), found.join().stream().map(PlayerArchive::id).toList());
        var page = repository.findPage(new PageRequest(1, 1)).toCompletableFuture();
        pending.remove().run();
        assertEquals(999, page.join().total());
        assertEquals("2", page.join().nextCursor());
        Collection<PlayerArchive> entities = new ArrayList<>(List.of(
                new PlayerArchive("p1", 0, "one"), new PlayerArchive("p2", 0, "two")));
        var saved = repository.saveAll(entities).toCompletableFuture();
        entities.clear();
        pending.remove().run();
        saved.join();
        ids.addAll(List.of("p1", "p2"));
        var deleted = repository.deleteAll(ids).toCompletableFuture();
        ids.clear();
        pending.remove().run();
        deleted.join();
    }

    /** 可变 Actor 数据必须在调用执行域编码；提交后变更不能影响将要写入的 payload。 */
    @Test
    void mutableEntityPayloadIsEncodedBeforeIoSubmission() {
        List<String> values = new ArrayList<>(List.of("before"));
        ArrayDeque<Runnable> pending = new ArrayDeque<>();
        var codec = new ZeroDataEntityCodec<String, MutableArchive>(
                new ZeroDataMappingIntrospector().inspect(MutableArchive.class), new MutableArchiveCodec(), 1);
        var store = new TestEnvelopeStore();
        var repository = new ZeroDataEnvelopeCrudRepository<>(codec, store, pending::add);
        var saved = repository.save(new MutableArchive("p", 0, values)).toCompletableFuture();
        assertFalse(saved.isDone());
        assertEquals(0, store.count());
        values.set(0, "after");
        pending.remove().run();
        saved.join();
        assertEquals(List.of("before"), codec.decode(store.findById(codec.encodeId("p")).orElseThrow()).values());
    }

    /** 执行器拒绝和驱动 Error 都必须完成异步收据，不能把调用挂起。 */
    @Test
    void executorRejectionAndDriverErrorsCompleteTheReturnedFuture() {
        RejectedExecutionException rejected = new RejectedExecutionException("full");
        var repository = new ZeroDataEnvelopeCrudRepository<>(newEntityCodec(), new TestEnvelopeStore(),
                task -> { throw rejected; });
        var failure = assertThrows(CompletionException.class, () -> repository.count().toCompletableFuture().join());
        assertEquals(DataErrorCode.READ_FAILED, ((ZeroException) failure.getCause()).errorCode());
        assertSame(rejected, failure.getCause().getCause());
        ArrayDeque<Runnable> pending = new ArrayDeque<>();
        AssertionError error = new AssertionError("driver error");
        var failedStore = proxyStore((proxy, method, args) -> { throw error; });
        var errorRepository = new ZeroDataEnvelopeCrudRepository<>(newEntityCodec(), failedStore, pending::add);
        var result = errorRepository.count().toCompletableFuture();
        pending.remove().run();
        var errorFailure = assertThrows(CompletionException.class, result::join);
        assertSame(error, errorFailure.getCause().getCause());
    }

    /** 版本溢出必须在任何后端写入前拒绝。 */
    @Test
    void invalidVersionsFailBeforeSubmittingIo() {
        var repository = new ZeroDataEnvelopeCrudRepository<>(newEntityCodec(), new TestEnvelopeStore(),
                task -> { throw new AssertionError("must not submit invalid write"); });
        for (long version : new long[] {-1, Long.MAX_VALUE}) {
            var failure = assertThrows(CompletionException.class,
                    () -> repository.save(new PlayerArchive("p", version, "bad")).toCompletableFuture().join());
            assertEquals(DataErrorCode.INVALID_ENTITY, ((ZeroException) failure.getCause()).errorCode());
        }
    }

    /** 构建严格记录 SPI 调用的测试 Store，未预期的退化路径直接失败。 */
    private ZeroDataEnvelopeStore proxyStore(final InvocationHandler handler) {
        return (ZeroDataEnvelopeStore) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ZeroDataEnvelopeStore.class}, handler);
    }

    /** 模拟在 Actor 内持有可变集合的实体。 @author zn */
    @ZeroDataObject(namespace = "game", collection = "mutable", schemaVersion = 1)
    private record MutableArchive(@ZeroDataId String id, @ZeroDataVersion long version,
            @ZeroDataField(order = 1) List<String> values) implements VersionedEntity<String> {
        /** 返回版本副本，集合仍由 Actor 持有，编码阶段必须快照。 */
        @Override public MutableArchive withVersion(final long value) { return new MutableArchive(id, value, values); }
    }

    /** 仅编码一个可变字段的测试 codec，无共享可变状态。 @author zn */
    private static final class MutableArchiveCodec implements ZeroPayloadCodec<MutableArchive> {
        /** @return codec 名称；不可空。 */
        @Override public String name() { return "mutable-test"; }
        /** @return 实体类型；不可空。 */
        @Override public Class<MutableArchive> messageType() { return MutableArchive.class; }
        /** 编码调用时字段内容；调用方负责 Actor 线程绑定。 */
        @Override public void write(final ZeroWriter writer, final MutableArchive entity) {
            writer.writeString(entity.id()); writer.writeLong(entity.version()); writer.writeString(entity.values().getFirst());
        }
        /** @return 新实体；不可空，集合不可变。 */
        @Override public MutableArchive read(final ZeroReader reader) {
            return new MutableArchive(reader.readString(), reader.readLong(), List.of(reader.readString()));
        }
    }

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
                        new TestEnvelopeStore(), Runnable::run);

        repository.save(new PlayerItemArchive("p1", 7L, 0L, "bag")).toCompletableFuture().join();
        PlayerItemArchive saved = repository.findById(new PlayerItemKey("p1", 7L))
                .toCompletableFuture()
                .join()
                .orElseThrow();

        assertEquals(1L, saved.version());
        assertEquals("bag", saved.slot());
    }

    /**
     * 验证调用方显式生成并保留主键，save 不会对无身份重试隐式新增行。
     */
    @Test
    void repositoryShouldGenerateMissingUuidStyleId() {
        ZeroDataObjectMetadata metadata = new ZeroDataMappingIntrospector().inspect(GeneratedArchive.class);
        ZeroDataEntityCodec<String, GeneratedArchive> codec =
                new ZeroDataEntityCodec<>(metadata, new GeneratedArchiveCodec(), 1);
        ZeroDataEnvelopeCrudRepository<String, GeneratedArchive> repository = new ZeroDataEnvelopeCrudRepository<>(
                codec,
                new TestEnvelopeStore(), Runnable::run);

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> repository.save(new GeneratedArchive(null, 0L, "created")).toCompletableFuture().join());
        GeneratedArchive prepared = codec.prepareForSave(new GeneratedArchive(null, 0L, "created"));
        repository.save(prepared).toCompletableFuture().join();
        GeneratedArchive saved = repository.findById("generated-id").toCompletableFuture().join().orElseThrow();

        assertEquals("generated-id", saved.id());
        assertEquals(1L, saved.version());
        assertEquals("created", saved.name());
    }

    /** 信封仓库分页结束位置计算不能因整数溢出而产生非法子列表范围。 */
    @Test
    void repositoryShouldHandleLargePageBoundsWithoutIntegerOverflow() {
        ZeroDataEnvelopeCrudRepository<String, PlayerArchive> repository = newRepository(new TestEnvelopeStore());
        repository.save(new PlayerArchive("p1", 0L, "created")).toCompletableFuture().join();

        PageResult<PlayerArchive> page = repository.findPage(
                        new PageRequest(Integer.MAX_VALUE - 1, Integer.MAX_VALUE))
                .toCompletableFuture()
                .join();

        assertEquals(0, page.items().size());
        assertEquals(1L, page.total());
        assertNull(page.nextCursor());
    }

    private ZeroDataEnvelopeCrudRepository<String, PlayerArchive> newRepository(final ZeroDataEnvelopeStore store) {
        return new ZeroDataEnvelopeCrudRepository<>(newEntityCodec(), store, Runnable::run);
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

    /** 多级接口用于验证泛型参数不会在继承边界丢失。 @author zn */
    private interface KeyContract<K> extends ZeroDataKeyCodec<K> { }

    /** 泛型编码器基类。 @author zn */
    private abstract static class BaseKeyCodec<K> implements KeyContract<K> { }

    /** 中间层继续转发类型变量。 @author zn */
    private abstract static class IntermediateKeyCodec<K> extends BaseKeyCodec<K> { }

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
    private static final class PlayerItemKeyCodec extends IntermediateKeyCodec<PlayerItemKey> {

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

        /** 测试存储显式实现原子条件写。 */
        @Override public synchronized boolean saveIfVersion(final ZeroDataEnvelope envelope, final long expected) {
            ZeroDataEnvelope old = envelopes.get(envelope.id());
            if (expected == 0 ? old != null : old == null || old.version() != expected) return false;
            envelopes.put(envelope.id(), envelope);
            return true;
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
