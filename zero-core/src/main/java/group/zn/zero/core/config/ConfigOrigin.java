package group.zn.zero.core.config;

/** 配置快照中值的来源类别；不携带路径、凭据或配置值。@author zn */
public enum ConfigOrigin {
    /** 应用显式赋值。 */
    PROGRAMMATIC,
    /** JVM 属性。 */
    SYSTEM_PROPERTY,
    /** 进程环境变量。 */
    ENVIRONMENT,
    /** 外部配置文件。 */
    FILE,
    /** 应用缺省值。 */
    DEFAULT
}
