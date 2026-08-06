package group.zn.zero.data.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.model.PageRequest;
import group.zn.zero.data.model.PageResult;
import group.zn.zero.data.model.VersionedEntity;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * 本地内存仓库测试。
 *
 * @author zn
 */
class InMemoryCrudRepositoryTest {

    /**
     * 验证保存会递增版本，旧版本写入会被拒绝。
     */
    @Test
    void repositoryShouldIncrementVersionAndRejectStaleWrite() {
        InMemoryCrudRepository<String, TestEntity> repository = new InMemoryCrudRepository<>();

        repository.save(new TestEntity("player-1", 0, "created")).toCompletableFuture().join();
        TestEntity saved = repository.findById("player-1").toCompletableFuture().join().orElseThrow();
        repository.save(new TestEntity(saved.id(), saved.version(), "updated")).toCompletableFuture().join();
        TestEntity updated = repository.findById("player-1").toCompletableFuture().join().orElseThrow();

        CompletionException exception = assertThrows(CompletionException.class, () ->
                repository.save(new TestEntity("player-1", saved.version(), "stale")).toCompletableFuture().join());

        assertEquals(1L, saved.version());
        assertEquals(2L, updated.version());
        assertEquals(DataErrorCode.VERSION_CONFLICT, ((ZeroException) exception.getCause()).errorCode());
    }

    /**
     * 验证批量查询和分页结果可用。
     */
    @Test
    void repositoryShouldSupportBatchAndPageQuery() {
        InMemoryCrudRepository<String, TestEntity> repository = new InMemoryCrudRepository<>();
        repository.saveAll(List.of(
                new TestEntity("player-1", 0, "one"),
                new TestEntity("player-2", 0, "two"),
                new TestEntity("player-3", 0, "three"))).toCompletableFuture().join();

        List<TestEntity> batch = repository.findByIds(List.of("player-3", "missing", "player-1"))
                .toCompletableFuture()
                .join();
        PageResult<TestEntity> page = repository.findPage(new PageRequest(0, 2)).toCompletableFuture().join();

        assertEquals(2, batch.size());
        assertEquals("player-3", batch.getFirst().id());
        assertEquals(3L, page.total());
        assertEquals(2, page.items().size());
        assertTrue(page.nextCursor() != null && !page.nextCursor().isBlank());
    }

    /**
     * 测试实体。
     *
     * @param id 主键。
     * @param version 版本号。
     * @param value 值。
     * @author zn
     */
    private record TestEntity(String id, long version, String value) implements VersionedEntity<String> {

        /**
         * 返回指定版本的新实体。
         *
         * @param version 新版本号。
         * @return 新实体；不可为空。
         */
        @Override
        public TestEntity withVersion(final long version) {
            return new TestEntity(id, version, value);
        }
    }
}
