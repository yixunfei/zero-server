package group.zn.zero.data.model;

import java.util.List;

/**
 * 分页结果。
 *
 * @param items 当前页数据。
 * @param nextCursor 下一页游标；可为空。
 * @param total 总条数。
 * @author zn
 */
public record PageResult<T>(List<T> items, String nextCursor, long total) {

    /**
     * 创建分页结果。
     *
     * @throws NullPointerException 当数据列表为空时抛出。
     */
    public PageResult {
        items = List.copyOf(java.util.Objects.requireNonNull(items, "items"));
    }
}
