package group.zn.zero.data.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

import group.zn.zero.data.repository.CrudRepository;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import group.zn.zero.data.model.VersionedEntity;
import org.junit.jupiter.api.Test;

/**
 * 数据适配器仓库注册表并发边界测试。
 *
 * @author zn
 */
class AbstractRepositoryAdapterTest {

    /** 重复注册不能静默替换运行中的仓库实例。 */
    @Test
    void duplicateRegistrationShouldFailWithoutReplacingExistingRepository() {
        TestAdapter adapter = new TestAdapter();
        CrudRepository<String, TestEntity> first = new InMemoryCrudRepository<>();
        CrudRepository<String, TestEntity> second = new InMemoryCrudRepository<>();

        adapter.registerRepository("players", first);

        assertThrows(IllegalArgumentException.class, () -> adapter.registerRepository("players", second));
        assertSame(first, adapter.repository("players").orElseThrow());
        assertEquals(1, adapter.repositoryNames().size());
    }

    /** 最小可用适配器，使用内存仓库作为注册表测试替身。 */
    private static final class TestAdapter extends AbstractRepositoryAdapter {

        /** 创建测试适配器。 */
        private TestAdapter() {
            super("test");
        }
    }

    /** 注册表测试实体。 */
    private record TestEntity(String id, long version) implements VersionedEntity<String> {

        /** 返回指定版本的实体。 */
        @Override
        public TestEntity withVersion(final long nextVersion) {
            return new TestEntity(id, nextVersion);
        }
    }
}
