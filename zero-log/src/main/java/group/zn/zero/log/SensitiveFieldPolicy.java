package group.zn.zero.log;

/**
 * 调用方可注入的附加敏感字段策略。
 *
 * <p>该策略只能收紧框架不可关闭的默认安全基线；返回 {@link SensitiveFieldAction#ALLOW}
 * 不能放宽默认拒绝或脱敏决定。实现必须无状态或线程安全、执行有界且不得执行外部 IO。
 *
 * @author zn
 */
@FunctionalInterface
public interface SensitiveFieldPolicy {

    /**
     * 判断字段处理动作。
     *
     * <p>框架只会把通过默认绝对禁止检查的内容传给附加策略。实现不得保存字段原值。
     *
     * @param fieldPath 原始字段路径；不可为空。
     * @param value 原始字段值；不可为空。
     * @return 附加处理动作；不可为空。
     */
    SensitiveFieldAction actionFor(String fieldPath, String value);

    /**
     * 返回不增加额外限制的策略。
     *
     * @return 无状态、线程安全的策略；不可为空。
     */
    static SensitiveFieldPolicy allowAll() {
        return (fieldPath, value) -> SensitiveFieldAction.ALLOW;
    }
}
