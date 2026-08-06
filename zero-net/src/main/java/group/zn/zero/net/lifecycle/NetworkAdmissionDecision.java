package group.zn.zero.net.lifecycle;

import group.zn.zero.net.error.NetErrorCode;
import java.util.Objects;

/**
 * 握手或鉴权决策。
 *
 * @param accepted 是否允许继续建立连接。
 * @param subjectId 鉴权主体标识；握手阶段或匿名连接可以为空字符串；不得进入指标标签。
 * @param errorCode 拒绝错误码；成功时为空。
 * @param rejectionReason 有界拒绝原因；成功时必须为 {@link ConnectionRejectionReason#NONE}。
 * @author zn
 */
public record NetworkAdmissionDecision(
        boolean accepted,
        String subjectId,
        NetErrorCode errorCode,
        ConnectionRejectionReason rejectionReason) {

    /**
     * 创建网络接入决策。
     *
     * @throws NullPointerException 当主体标识或拒绝原因为空时抛出。
     * @throws IllegalArgumentException 当成功与失败字段组合不一致时抛出。
     */
    public NetworkAdmissionDecision {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(rejectionReason, "rejectionReason");
        if (accepted && (errorCode != null || rejectionReason != ConnectionRejectionReason.NONE)) {
            throw new IllegalArgumentException("accepted decision must not contain rejection fields");
        }
        if (!accepted && (errorCode == null || rejectionReason == ConnectionRejectionReason.NONE)) {
            throw new IllegalArgumentException("rejected decision must contain errorCode and rejectionReason");
        }
    }

    /**
     * 创建匿名成功决策。
     *
     * @return 成功决策；不可为空；线程安全。
     */
    public static NetworkAdmissionDecision allow() {
        return authenticated("");
    }

    /**
     * 创建带鉴权主体的成功决策。
     *
     * @param subjectId 鉴权主体标识；不可为空；可以为空字符串。
     * @return 成功决策；不可为空；线程安全。
     */
    public static NetworkAdmissionDecision authenticated(final String subjectId) {
        return new NetworkAdmissionDecision(true, subjectId, null, ConnectionRejectionReason.NONE);
    }

    /**
     * 创建拒绝决策。
     *
     * @param errorCode 网络错误码；不可为空。
     * @param reason 有界拒绝原因；不可为空且不能为 NONE。
     * @return 拒绝决策；不可为空；线程安全。
     */
    public static NetworkAdmissionDecision rejected(
            final NetErrorCode errorCode,
            final ConnectionRejectionReason reason) {
        return new NetworkAdmissionDecision(false, "", errorCode, reason);
    }
}
