package group.zn.zero.monitor;

/**
 * 指标标签附加安全策略。
 *
 * <p>框架会先执行不可关闭的全局标签名称、数量、长度、控制字符和 IP 字面量校验，
 * 再调用本策略。因此实现只能通过返回 {@code false} 增加限制，无法放宽框架底线。
 * 实现不得修改输入，不应执行阻塞 IO；同一策略实例可能被多个注册表复用，建议保持无状态和线程安全。
 *
 * @author zn
 */
public interface MetricLabelPolicy {

    /**
     * 判断定义中的标签名称是否满足附加限制。
     *
     * @param metricName 已通过全局校验的指标名称；不可为空。
     * @param labelName 已通过全局校验的标签名称；不可为空。
     * @return {@code true} 表示本策略不额外拒绝；不改变任何数据；实现应线程安全。
     */
    default boolean allowsDefinitionLabel(final String metricName, final String labelName) {
        return true;
    }

    /**
     * 判断样本中的标签值是否满足附加限制。
     *
     * @param metricName 已通过全局校验的指标名称；不可为空。
     * @param labelName 已通过全局校验的标签名称；不可为空。
     * @param labelValue 已通过全局校验的标签值；不可为空。
     * @return {@code true} 表示本策略不额外拒绝；不改变任何数据；实现应线程安全。
     */
    default boolean allowsSampleLabel(
            final String metricName,
            final String labelName,
            final String labelValue) {
        return true;
    }
}
