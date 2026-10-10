package group.zn.zero.gm;

/**
 * 可信审批服务提供的第二复核人验证端口；客户端姓名不是身份凭证。
 * 实现必须校验审批令牌的有效期，并绑定操作者、命令及全部参数、目标和复核人权限。
 * 实现自行保证并发安全，不得保存请求或在日志中暴露令牌。
 * @author zn
 */
@FunctionalInterface
public interface GmSecondReviewerVerifier {
    /**
     * 核验独立第二人的审批事实；无业务数据修改。
     * @param request 已解析的完整操作；不可为空。
     * @param target 实际目标；不可为空。
     * @param token 不透明审批凭证；不得仅按上下文状态放行。
     * @param claimedReviewer 客户端声明，仅供与可信审批主体比对。
     * @return 经可信服务确认的规范主体；无有效复核返回空字符串。
     * @throws RuntimeException 验证服务不可用时抛出，授权边界保持拒绝。
     */
    String verify(GmCommandExecutionRequest request, String target, String token, String claimedReviewer);

    /** @return 默认拒绝的无状态线程安全端口；无副作用。 */
    static GmSecondReviewerVerifier failClosed() {
        return (request, target, token, reviewer) -> "";
    }
}
