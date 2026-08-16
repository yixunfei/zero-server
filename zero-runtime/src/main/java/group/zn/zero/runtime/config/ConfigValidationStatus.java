package group.zn.zero.runtime.config;

/**
 * 公开报告中的配置解析状态。
 *
 * @author zn
 */
public enum ConfigValidationStatus {

    /** 从显式来源成功解析。 */
    RESOLVED,

    /** 使用 schema 默认值。 */
    DEFAULTED,

    /** optional key 没有值。 */
    ABSENT
}
