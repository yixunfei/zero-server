package group.zn.zero.examples.multiplayer;

import java.util.Locale;
import java.util.Set;

/**
 * 角色契约的配置说明入口；不启动角色、不注册 RPC handler，也不宣称服务就绪。
 * 真实连通验证使用 ExternalInfrastructureProbe；完整 RPC 装配见 center-logic-kafka 示例。
 * @author zn
 */
public final class RoleLauncherMain {
    private RoleLauncherMain() { }

    /**
     * 校验角色名并提示下一步；线程安全，不修改数据或创建连接。
     * @param args 可为空数组；第一个参数为 login、center 或 logic，缺省 login。
     * @throws IllegalArgumentException 参数数量或角色名非法。
     */
    public static void main(final String[] args) {
        String role = args.length == 0 ? "login" : args[0].toLowerCase(Locale.ROOT);
        if (args.length > 1 || !Set.of("login", "center", "logic").contains(role)) {
            throw new IllegalArgumentException("usage: RoleLauncherMain [login|center|logic]");
        }
        System.out.println("role=" + role + "|mode=contract-guide|rpcHandlers=0"
                + "|next=examples/modular-composition/center-logic-kafka/README.md");
    }
}
