package group.zn.zero.runtime.spi;

/**
 * profile policy 使用的组件类别；不参与 provider 自动选择。
 *
 * @author zn
 */
public enum ComponentKind {

    /** 框架基础、中立组件。 */
    FOUNDATION,

    /** 本地或内存实现。 */
    LOCAL,

    /** 连接进程外基础设施的实现。 */
    EXTERNAL,

    /** 业务项目组件。 */
    BUSINESS,

    /** 运维、诊断或管理组件。 */
    OPERATIONS
}
