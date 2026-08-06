package group.zn.zero.gm;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * GM 操作上下文。
 *
 * @param operator 操作者账号或系统主体；不可为空。
 * @param operatorIp 操作者来源 IP；不可为空。
 * @param traceId 链路追踪标识；不可为空。
 * @param roles 操作者角色集合；不可为空；构造后不可变；无序；可能为空；线程安全。
 * @param permissions 操作者权限集合；不可为空；构造后不可变；无序；可能为空；线程安全。
 * @param approvalId 审批单号；不可为空，空字符串表示本轮未接入审批。
 * @param approvalState 审批状态；不可为空，空字符串表示本轮未接入审批。
 * @param attributes 扩展上下文字段；不可为空；构造后不可变；无序；可能为空；线程安全。
 * @author zn
 */
public record GmCommandContext(
        String operator,
        String operatorIp,
        String traceId,
        Set<String> roles,
        Set<String> permissions,
        String approvalId,
        String approvalState,
        Map<String, String> attributes) {

    /**
     * 创建 GM 操作上下文。
     *
     * @throws NullPointerException 当操作者、IP、traceId、角色、权限或扩展字段为空时抛出。
     */
    public GmCommandContext {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(operatorIp, "operatorIp");
        Objects.requireNonNull(traceId, "traceId");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        approvalId = approvalId == null ? "" : approvalId;
        approvalState = approvalState == null ? "" : approvalState;
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    /**
     * 创建无角色、无权限、无审批信息的最小 GM 操作上下文。
     *
     * @param operator 操作者账号或系统主体；不可为空。
     * @param operatorIp 操作者来源 IP；不可为空。
     * @param traceId 链路追踪标识；不可为空。
     * @return GM 操作上下文；不可为空；无数据变更；线程安全。
     * @throws NullPointerException 当操作者、IP 或 traceId 为空时抛出。
     */
    public static GmCommandContext simple(final String operator, final String operatorIp, final String traceId) {
        return new GmCommandContext(operator, operatorIp, traceId, Set.of(), Set.of(), "", "", Map.of());
    }
}
