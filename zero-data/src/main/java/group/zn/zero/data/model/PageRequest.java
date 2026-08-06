package group.zn.zero.data.model;

/**
 * 分页请求。
 *
 * @param offset 起始偏移量。
 * @param limit 每页条数。
 * @author zn
 */
public record PageRequest(int offset, int limit) {

    /**
     * 创建分页请求。
     *
     * @throws IllegalArgumentException 当偏移量或条数非法时抛出。
     */
    public PageRequest {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
}
