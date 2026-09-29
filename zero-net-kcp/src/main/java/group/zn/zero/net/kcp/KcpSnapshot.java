package group.zn.zero.net.kcp;

import java.util.Map;

/**
 * 服务指标的近实时快照；字段独立采样而非事务快照，不含身份和密钥。
 * @param sessions 全部票据会话。
 * @param connected 已绑定会话。
 * @param receivedDatagrams 收到数据报。
 * @param sentDatagrams 提交输出数据报。
 * @param rejectedDatagrams 拒绝包。
 * @param failures 异常数。
 * @param updates 算法更新次数。
 * @param flushes socket 刷新次数。
 * @param pendingSendBytes 发送预算。
 * @param pendingInboundBytes 业务预算。
 * @param reasons 不可变有序原因计数；可能为空，线程安全。
 * @author zn
 */
public record KcpSnapshot(long sessions, long connected, long receivedDatagrams, long sentDatagrams,
        long rejectedDatagrams, long failures, long updates, long flushes,
        long pendingSendBytes, long pendingInboundBytes, Map<String, Long> reasons) {
    /** 复制原因计数；线程安全，无外部修改。 */
    public KcpSnapshot { reasons = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(reasons)); }
}
