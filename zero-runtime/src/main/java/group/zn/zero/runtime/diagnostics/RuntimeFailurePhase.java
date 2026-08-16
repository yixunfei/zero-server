package group.zn.zero.runtime.diagnostics;

/**
 * runtime 装配失败阶段。
 *
 * @author zn
 */
public enum RuntimeFailurePhase {

    /** catalog 或 descriptor 注册。 */
    REGISTRATION,

    /** selection 和依赖图规划。 */
    PLANNING,

    /** typed 配置解析和校验。 */
    CONFIGURATION,

    /** provider 创建。 */
    CREATE,

    /** lifecycle 启动。 */
    START,

    /** startup health。 */
    HEALTH,

    /** lifecycle 停止。 */
    STOP,

    /** build resource 关闭。 */
    CLOSE,

    /** profile policy 校验。 */
    POLICY
}
