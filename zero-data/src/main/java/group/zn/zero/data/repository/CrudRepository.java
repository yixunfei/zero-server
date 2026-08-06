package group.zn.zero.data.repository;

import group.zn.zero.data.model.PageRequest;
import group.zn.zero.data.model.PageResult;
import group.zn.zero.data.model.VersionedEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 数据仓库扩展抽象。
 *
 * @param <ID> 主键类型。
 * @param <T> 实体类型。
 * @author zn
 */
public interface CrudRepository<ID, T extends VersionedEntity<ID>> extends Repository<ID, T> {

    /**
     * 查询所有对象。
     *
     * @return 查询结果列表；不可为空；可能为空；线程安全性由实现声明。
     */
    CompletionStage<List<T>> findAll();

    /**
     * 根据 ID 列表批量查询。
     *
     * @param ids 对象 ID 列表；不可为空。
     * @return 查询结果列表；不可为空；可能为空；线程安全性由实现声明。
     */
    CompletionStage<List<T>> findByIds(Collection<ID> ids);

    /**
     * 批量保存对象。
     *
     * @param entities 对象集合；不可为空。
     * @return 保存完成信号；不可为空；线程安全性由实现声明。
     */
    CompletionStage<Void> saveAll(Collection<T> entities);

    /**
     * 根据 ID 删除对象。
     *
     * @param id 对象 ID；不可为空。
     * @return 删除完成信号；不可为空；线程安全性由实现声明。
     */
    CompletionStage<Void> deleteById(ID id);

    /**
     * 批量删除对象。
     *
     * @param ids 对象 ID 列表；不可为空。
     * @return 删除完成信号；不可为空；线程安全性由实现声明。
     */
    CompletionStage<Void> deleteAll(Collection<ID> ids);

    /**
     * 判断对象是否存在。
     *
     * @param id 对象 ID；不可为空。
     * @return true 表示存在；线程安全性由实现声明。
     */
    CompletionStage<Boolean> existsById(ID id);

    /**
     * 统计对象数量。
     *
     * @return 对象数量；不可为空；线程安全性由实现声明。
     */
    CompletionStage<Long> count();

    /**
     * 查询分页结果。
     *
     * @param request 分页请求；不可为空。
     * @return 分页结果；不可为空；线程安全性由实现声明。
     */
    CompletionStage<PageResult<T>> findPage(PageRequest request);
}
