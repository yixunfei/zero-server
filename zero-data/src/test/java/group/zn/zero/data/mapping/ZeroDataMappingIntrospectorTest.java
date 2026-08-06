package group.zn.zero.data.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.model.VersionedEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 数据映射元数据解析器测试。
 *
 * @author zn
 */
class ZeroDataMappingIntrospectorTest {

    /**
     * 验证基础数据对象、引用列表和字段顺序可被解析。
     */
    @Test
    void introspectorShouldReadObjectFieldsAndReferences() {
        ZeroDataObjectMetadata metadata = new ZeroDataMappingIntrospector().inspect(PlayerArchive.class);

        assertEquals("game", metadata.namespace());
        assertEquals("player", metadata.collection());
        assertEquals(1, metadata.schemaVersion());
        assertTrue(metadata.idField().isPresent());
        assertTrue(metadata.versionField().isPresent());
        assertEquals(4, metadata.fields().size());
        assertEquals(ZeroDataFieldKind.REFERENCE_LIST, metadata.fields().get(3).kind());
        assertEquals("item", metadata.fields().get(3).collection().orElseThrow());
    }

    /**
     * 验证组合键字段会按顺序进入元数据。
     */
    @Test
    void introspectorShouldReadCompositeKeyParts() {
        ZeroDataObjectMetadata metadata = new ZeroDataMappingIntrospector().inspect(PlayerItemArchive.class);

        assertTrue(metadata.compositeKey());
        assertEquals(PlayerItemKeyCodec.class, metadata.keyCodecType());
        assertEquals(2, metadata.keyParts().size());
        assertEquals("playerId", metadata.keyParts().get(0).storageName());
        assertEquals("itemId", metadata.keyParts().get(1).storageName());
    }

    /**
     * 验证嵌套数据对象必须显式声明关系。
     */
    @Test
    void introspectorShouldRejectImplicitNestedDataObject() {
        ZeroException exception = assertThrows(ZeroException.class,
                () -> new ZeroDataMappingIntrospector().inspect(InvalidNestedPlayerArchive.class));

        assertEquals(DataErrorCode.MAPPING_INVALID, exception.errorCode());
    }

    /**
     * 验证嵌入值对象可以通过显式注解声明。
     */
    @Test
    void introspectorShouldAllowExplicitEmbeddedDataObject() {
        ZeroDataObjectMetadata metadata = new ZeroDataMappingIntrospector().inspect(EmbeddedPlayerArchive.class);

        assertEquals(ZeroDataFieldKind.EMBEDDED, metadata.fields().get(2).kind());
    }

    /**
     * 验证 ID 生成器配置会进入对象元数据。
     */
    @Test
    void introspectorShouldReadIdGenerator() {
        ZeroDataObjectMetadata metadata = new ZeroDataMappingIntrospector().inspect(GeneratedIdArchive.class);

        assertEquals(UuidZeroDataKeyGenerator.class, metadata.idGeneratorType());
    }

    /**
     * 玩家数据对象。
     *
     * @param id 玩家 ID。
     * @param version 版本号。
     * @param name 名称。
     * @param itemIds 道具引用列表。
     * @author zn
     */
    @ZeroDataObject(namespace = "game", collection = "player", schemaVersion = 1)
    private record PlayerArchive(
            @ZeroDataId String id,
            @ZeroDataVersion long version,
            @ZeroDataField(order = 1, name = "name") String name,
            @ZeroDataField(order = 2, name = "itemIds")
            @ZeroDataReferenceList(target = ItemArchive.class)
            List<ItemKey> itemIds) implements VersionedEntity<String> {

        /**
         * 返回指定版本的新实体。
         *
         * @param version 新版本号。
         * @return 新实体；不可为空。
         */
        @Override
        public PlayerArchive withVersion(final long version) {
            return new PlayerArchive(id, version, name, itemIds);
        }
    }

    /**
     * 道具数据对象。
     *
     * @param id 道具 ID。
     * @param version 版本号。
     * @param count 数量。
     * @author zn
     */
    @ZeroDataObject(namespace = "game", collection = "item", schemaVersion = 1)
    private record ItemArchive(
            @ZeroDataId String id,
            @ZeroDataVersion long version,
            @ZeroDataField(order = 1, name = "count") int count) implements VersionedEntity<String> {

        /**
         * 返回指定版本的新实体。
         *
         * @param version 新版本号。
         * @return 新实体；不可为空。
         */
        @Override
        public ItemArchive withVersion(final long version) {
            return new ItemArchive(id, version, count);
        }
    }

    /**
     * 玩家道具组合键数据对象。
     *
     * @param playerId 玩家 ID。
     * @param itemId 道具 ID。
     * @param version 版本号。
     * @param count 数量。
     * @author zn
     */
    @ZeroDataObject(namespace = "game", collection = "player_item", schemaVersion = 1)
    @ZeroDataCompositeKey(codec = PlayerItemKeyCodec.class)
    private record PlayerItemArchive(
            @ZeroDataKeyPart(order = 1, name = "playerId") String playerId,
            @ZeroDataKeyPart(order = 2, name = "itemId") long itemId,
            @ZeroDataVersion long version,
            @ZeroDataField(order = 1, name = "count") int count) {
    }

    /**
     * 非法嵌套玩家对象。
     *
     * @param id 玩家 ID。
     * @param version 版本号。
     * @param items 道具对象列表。
     * @author zn
     */
    @ZeroDataObject(namespace = "game", collection = "invalid_player", schemaVersion = 1)
    private record InvalidNestedPlayerArchive(
            @ZeroDataId String id,
            @ZeroDataVersion long version,
            @ZeroDataField(order = 1, name = "items") List<ItemArchive> items) {
    }

    /**
     * 显式嵌入玩家对象。
     *
     * @param id 玩家 ID。
     * @param version 版本号。
     * @param item 道具对象。
     * @author zn
     */
    @ZeroDataObject(namespace = "game", collection = "embedded_player", schemaVersion = 1)
    private record EmbeddedPlayerArchive(
            @ZeroDataId String id,
            @ZeroDataVersion long version,
            @ZeroDataField(order = 1, name = "item") @ZeroDataEmbedded ItemArchive item) {
    }

    /**
     * 自动 ID 数据对象。
     *
     * @param id 对象 ID。
     * @param version 版本号。
     * @author zn
     */
    @ZeroDataObject(namespace = "game", collection = "generated", schemaVersion = 1)
    private record GeneratedIdArchive(
            @ZeroDataId(generator = UuidZeroDataKeyGenerator.class) String id,
            @ZeroDataVersion long version) {
    }

    /**
     * 道具键。
     *
     * @param id 道具 ID。
     * @author zn
     */
    private record ItemKey(String id) {
    }

    /**
     * 玩家道具键。
     *
     * @param playerId 玩家 ID。
     * @param itemId 道具 ID。
     * @author zn
     */
    private record PlayerItemKey(String playerId, long itemId) {
    }

    /**
     * 玩家道具键编码器。
     *
     * @author zn
     */
    private static final class PlayerItemKeyCodec implements ZeroDataKeyCodec<PlayerItemKey> {

        /**
         * 创建玩家道具键编码器。
         */
        private PlayerItemKeyCodec() {
        }

        /**
         * 编码玩家道具键。
         *
         * @param key 玩家道具键；不可为空。
         * @return 编码值；不可为空；线程安全。
         */
        @Override
        public String encode(final PlayerItemKey key) {
            return key.playerId() + ":" + key.itemId();
        }

        /**
         * 解码玩家道具键。
         *
         * @param value 编码值；不可为空。
         * @return 玩家道具键；不可为空；线程安全。
         */
        @Override
        public PlayerItemKey decode(final String value) {
            String[] parts = value.split(":", 2);
            return new PlayerItemKey(parts[0], Long.parseLong(parts[1]));
        }
    }
}
