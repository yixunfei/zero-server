package group.zn.zero.net.kcp;

/** 显式业务场景；参数是经过测试的起点，不隐含 UDP 加密或不可靠消息语义。 @author zn */
public enum KcpProfile {
    /** 小帧动作/帧同步，立即刷新。 */
    LOW_LATENCY(0),
    /** 房间/RPG 状态同步，默认方案。 */
    BALANCED(1),
    /** 移动弱网，保守 MTU 和较宽恢复时限。 */
    MOBILE(2),
    /** 大量低频连接，降低空闲调度。 */
    LOW_FREQUENCY(3),
    /** 有限批量数据；大文件仍应使用 TCP。 */
    BULK(4);

    /** ZKCI v1 稳定 ID，不依赖枚举声明顺序。 */
    private final int wireId;
    KcpProfile(final int wireId) { this.wireId = wireId; }
    /** @return 稳定控制面 ID；线程安全，无变更。 */
    public int wireId() { return wireId; }
    /** @param id 稳定 ID。 @return 对应预设；线程安全，未知 ID 抛出参数异常。 */
    public static KcpProfile fromWireId(final int id) {
        for (KcpProfile profile : values()) if (profile.wireId == id) return profile;
        throw new IllegalArgumentException("unknown KCP profile ID");
    }

    /** @return 本场景的独立不可变配置；线程安全，无外部变更。 */
    public KcpOptions options() { return KcpOptions.builder(this).build(); }
}
