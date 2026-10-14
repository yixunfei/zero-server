package group.zn.zero.data.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.EnvelopeRepositoryFactory;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.mapping.ZeroDataId;
import group.zn.zero.data.mapping.ZeroDataObject;
import group.zn.zero.data.mapping.ZeroDataVersion;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/** 便利内存与标准 local 仓库共用的版本、删除和批量失败契约。 @author zn */
class LocalRepositoryVersionContractTest {
    /** 相同操作序列在两种实现上得到相同版本与错误分类。 */
    @Test void localImplementationsShareVersionAndBatchFailureSemantics() {
        try (var factory = EnvelopeRepositoryFactory.inMemory(Runnable::run)) {
            var standard = factory.create(RepositoryDefinition.of("versions", String.class, Entity.class, new Codec(), 1));
            for (CrudRepository<String, Entity> repository : List.of(new InMemoryCrudRepository<String, Entity>(), standard)) {
                assertFailure(DataErrorCode.INVALID_ENTITY, repository.save(new Entity("negative", -1)));
                assertFailure(DataErrorCode.INVALID_ENTITY, repository.save(new Entity("maximum", Long.MAX_VALUE)));
                assertFailure(DataErrorCode.VERSION_CONFLICT, repository.save(new Entity("missing", 1)));
                assertEquals(0L, repository.count().toCompletableFuture().join());
                repository.save(new Entity("player", 0)).toCompletableFuture().join();
                Entity first = repository.findById("player").toCompletableFuture().join().orElseThrow();
                assertEquals(1L, first.version());
                repository.save(first).toCompletableFuture().join();
                assertFailure(DataErrorCode.VERSION_CONFLICT, repository.save(first));
                assertFailure(DataErrorCode.VERSION_CONFLICT, repository.saveAll(List.of(first)));
                repository.deleteById("player").toCompletableFuture().join();
                assertFailure(DataErrorCode.VERSION_CONFLICT, repository.save(first));
                assertTrue(repository.findById("player").toCompletableFuture().join().isEmpty());
                repository.save(new Entity("player", 0)).toCompletableFuture().join();
                assertEquals(1L, repository.findById("player").toCompletableFuture().join().orElseThrow().version());
            }
        }
    }

    private static void assertFailure(final DataErrorCode code, final CompletionStage<?> result) {
        var failure = assertThrows(CompletionException.class, () -> result.toCompletableFuture().join());
        assertEquals(code, assertInstanceOf(ZeroException.class, failure.getCause()).errorCode());
    }

    /** 最小不可变测试实体。 @param id 主键。 @param version CAS 版本。 @author zn */
    @ZeroDataObject(namespace = "contract", collection = "versions", schemaVersion = 1)
    public record Entity(@ZeroDataId String id, @ZeroDataVersion long version) implements VersionedEntity<String> {
        /** 返回新版本快照；线程安全，不修改当前实体。 @param next 新版本。 @return 不可变实体。 */
        @Override public Entity withVersion(final long next) { return new Entity(id, next); }
    }

    /** 测试实体的二进制编码，编码不修改实体。 @author zn */
    private static final class Codec implements ZeroPayloadCodec<Entity> {
        /** @return 稳定编码名称；线程安全。 */
        @Override public String name() { return "version-contract"; }
        /** @return 实体类型；线程安全。 */
        @Override public Class<Entity> messageType() { return Entity.class; }
        /** 写入调用方独占缓冲区。 @param writer 输出。 @param value 不可变实体。 */
        @Override public void write(final ZeroWriter writer, final Entity value) {
            writer.writeString(value.id());
            writer.writeLong(value.version());
        }
        /** 读取调用方独占缓冲区。 @param reader 输入。 @return 不可变实体。 */
        @Override public Entity read(final ZeroReader reader) { return new Entity(reader.readString(), reader.readLong()); }
    }
}
