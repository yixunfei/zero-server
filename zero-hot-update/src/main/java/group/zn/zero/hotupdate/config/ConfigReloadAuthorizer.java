package group.zn.zero.hotupdate.config;

import group.zn.zero.hotupdate.HotUpdateRequest;

/**
 * 应用注入的可信授权端口；不得仅把 request.operator 当作身份凭证。
 * 实现须验证应用身份、表级权限及请求来源；在受管 IO 域调用，必须线程安全。
 * @author zn
 */
@FunctionalInterface
public interface ConfigReloadAuthorizer {
    /**
     * 在读取候选文件及变更快照前授权。异常按拒绝处理并交由观察器审计。
     * @param tableName 实际目标表。 @param request 请求上下文，不是已认证身份。
     * @param automatic true 表示受管 watcher 触发。 @return 是否允许本次重载。
     */
    boolean allows(String tableName, HotUpdateRequest request, boolean automatic);
}
