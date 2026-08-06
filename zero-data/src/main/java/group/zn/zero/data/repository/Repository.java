package group.zn.zero.data.repository;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 数据仓库基础抽象。
 *
 * @param <ID> 主键类型。
 * @param <T> 实体类型。
 * @author zn
 */
public interface Repository<ID, T> {

    /**
     * 根据 ID 查询对象。
     *
     * @param id 对象 ID；不可为空。
     * @return 查询结果；为空表示不存在；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 查询失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<Optional<T>> findById(ID id);

    /**
     * 保存对象。
     *
     * @param entity 对象；不可为空。
     * @return 保存完成信号；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 保存失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<Void> save(T entity);
}

