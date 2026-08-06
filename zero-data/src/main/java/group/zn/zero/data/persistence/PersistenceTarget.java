package group.zn.zero.data.persistence;

import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.data.repository.CrudRepository;
import java.util.Objects;

/**
 * 持久化目标。
 *
 * @param name 目标名称，通常对应仓库名称。
 * @param repository 目标仓库。
 * @param <ID> 主键类型。
 * @param <T> 实体类型。
 * @author zn
 */
public record PersistenceTarget<ID, T extends VersionedEntity<ID>>(
        String name,
        CrudRepository<ID, T> repository) {

    /**
     * 创建持久化目标。
     *
     * @throws NullPointerException 当必要字段为空时抛出。
     * @throws IllegalArgumentException 当目标名称为空白时抛出。
     */
    public PersistenceTarget {
        name = requireText(name, "name");
        repository = Objects.requireNonNull(repository, "repository");
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
