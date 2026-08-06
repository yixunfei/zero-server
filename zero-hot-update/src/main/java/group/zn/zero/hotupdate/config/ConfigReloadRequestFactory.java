package group.zn.zero.hotupdate.config;

import group.zn.zero.hotupdate.HotUpdateLevel;
import group.zn.zero.hotupdate.HotUpdateRequest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 初始加载与本地 watcher 自动重载请求工厂。
 *
 * @author zn
 */
@FunctionalInterface
public interface ConfigReloadRequestFactory {

    /**
     * 创建自动配置重载请求。
     *
     * @param tableName 稳定表名；不可为空。
     * @param requestedVersion 计划加载的本地版本；大于 0。
     * @param requestedAt 请求时间；不可为空。
     * @return 热更请求；不可为空。
     */
    HotUpdateRequest create(String tableName, long requestedVersion, Instant requestedAt);

    /**
     * 创建本地自动重载请求工厂。
     *
     * <p>每个请求使用随机 traceId；请求类型只包含低基数表名，不包含路径或 CSV 内容。
     *
     * @param operator 本地操作者标识；不可为空白。
     * @return 请求工厂；不可为空；线程安全。
     * @throws IllegalArgumentException 当操作者为空白时抛出。
     */
    static ConfigReloadRequestFactory local(final String operator) {
        String checkedOperator = Objects.requireNonNull(operator, "operator");
        if (checkedOperator.isBlank()) {
            throw new IllegalArgumentException("operator must not be blank");
        }
        return (tableName, version, requestedAt) -> new HotUpdateRequest(
                "config:" + tableName,
                HotUpdateLevel.SEAMLESS,
                Long.toString(version),
                checkedOperator,
                UUID.randomUUID().toString(),
                requestedAt);
    }
}
