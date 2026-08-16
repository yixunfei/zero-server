package group.zn.zero.runtime.config;

/**
 * typed runtime 配置来源类别。
 *
 * @author zn
 */
public enum ConfigSourceKind {

    /** 程序化或 ZeroConfig 输入。 */
    PROGRAMMATIC,

    /** JVM system property。 */
    SYSTEM_PROPERTY,

    /** 进程环境变量。 */
    ENVIRONMENT,

    /** 显式文件来源。 */
    FILE,

    /** 显式远程配置来源。 */
    REMOTE,

    /** schema 默认值；不能作为外部 ConfigSource。 */
    DEFAULT
}
