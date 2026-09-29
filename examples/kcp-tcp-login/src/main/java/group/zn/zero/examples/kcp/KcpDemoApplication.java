package group.zn.zero.examples.kcp;

import group.zn.zero.net.kcp.KcpProfile;

/** 无外部服务的独立运行入口，真实 TLS/UDP、框架资源和恢复状态机；不是 JUnit。 @author zn */
public final class KcpDemoApplication {
    private KcpDemoApplication() { }
    /** @param args --demo [PROFILE|ALL]。 @throws Exception 任一阶段失败即非零退出。 */
    public static void main(final String[] args) throws Exception {
        if (args.length == 0 || !args[0].equals("--demo")) throw new IllegalArgumentException("explicit --demo required");
        KcpProfile[] profiles = args.length < 2 || args[1].equals("ALL") ? KcpProfile.values()
                : new KcpProfile[]{KcpProfile.valueOf(args[1])};
        for (KcpProfile profile : profiles) {
            try (var server = new KcpDemoServer(profile)) {
                KcpDemoClient.run(server.port(), server.certificateFile().toPath());
                System.out.println("SERVER " + server.server().snapshot());
            }
        }
    }
}
