package group.zn.zero.runtime.config;

/**
 * 配置变更语义。
 *
 * @author zn
 */
public enum ConfigReloadability {

    /** 仅启动前解析，变化需要重建 runtime。 */
    STARTUP_ONLY,

    /** owner 明确支持运行期原子更新。 */
    RELOADABLE
}
