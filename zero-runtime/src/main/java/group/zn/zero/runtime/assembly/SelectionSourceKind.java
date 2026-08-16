package group.zn.zero.runtime.assembly;

/**
 * provider 显式选择来源。
 *
 * @author zn
 */
public enum SelectionSourceKind {

    /** 命名 preset。 */
    PRESET,

    /** 部署清单或配置。 */
    DEPLOYMENT,

    /** 程序化显式选择。 */
    PROGRAMMATIC,

    /** 语义明确的覆盖操作。 */
    OVERRIDE
}
