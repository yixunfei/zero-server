package group.zn.zero.net.lifecycle;

import group.zn.zero.net.ConnectionAttributeKey;

/**
 * 生产网络生命周期标准连接属性。
 *
 * <p>属性用于连接级诊断与跨执行域只读传递，不承载 token、密码或可直接修改的玩家对象。</p>
 *
 * @author zn
 */
public final class ProductionNetworkConnectionAttributes {

    /**
     * 当前生命周期状态。
     */
    public static final ConnectionAttributeKey<ConnectionLifecycleState> STATE =
            ConnectionAttributeKey.of("zero.net.lifecycle.state", ConnectionLifecycleState.class);

    /**
     * 连接级 traceId。
     */
    public static final ConnectionAttributeKey<String> TRACE_ID =
            ConnectionAttributeKey.of("zero.net.lifecycle.traceId", String.class);

    /**
     * 鉴权主体标识；不得作为指标标签。
     */
    public static final ConnectionAttributeKey<String> SUBJECT_ID =
            ConnectionAttributeKey.of("zero.net.lifecycle.subjectId", String.class);

    private ProductionNetworkConnectionAttributes() {
    }
}
