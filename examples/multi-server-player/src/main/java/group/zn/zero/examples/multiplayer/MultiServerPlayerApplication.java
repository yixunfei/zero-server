package group.zn.zero.examples.multiplayer;

import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.cache.InMemoryCacheService;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import group.zn.zero.examples.multiplayer.role.LoginServer;
import group.zn.zero.examples.multiplayer.role.LogicServer;
import group.zn.zero.examples.multiplayer.rpc.CreateCharacterRequest;
import group.zn.zero.examples.multiplayer.rpc.LoginRequest;
import group.zn.zero.examples.multiplayer.rpc.RegisterRequest;
import java.util.List;

/**
 * 在单 JVM 中顺序调用登录服和逻辑服职责；外部探针是独立入口，不参与此业务链路。
 * 每次运行拥有独立内存依赖，结束时先关闭玩家服务再关闭调度器。
 * @author zn
 */
public final class MultiServerPlayerApplication {
    private MultiServerPlayerApplication() { }
    /**
     * 在普通命令行线程执行注册、登录、创建角色、断线和重连，并打印结果。
     * @param args 未使用参数；不读取账号或凭据，不修改外部数据。
     * @throws group.zn.zero.core.error.ZeroException 业务或框架操作失败；异常向上传播，资源仍关闭。
     */
    public static void main(final String[] args) {
        try (LocalActorScheduler scheduler = new LocalActorScheduler();
                PlayerLifecycleService players = new PlayerLifecycleService(scheduler,
                        new CenterRouter(List.of("logic-1", "logic-2")),
                        new InMemoryCrudRepository<>(), new InMemoryCacheService<>())) {
            LoginServer login = new LoginServer(players);
            LogicServer logic = new LogicServer(players);
            login.register(new RegisterRequest("alice", "trace-register")).orThrow();
            PlayerSession first = login.login(new LoginRequest("alice", "token-alice", "trace-login")).orThrow();
            logic.create(new CreateCharacterRequest(first, "Alice", "trace-character")).orThrow();
            logic.disconnect(new group.zn.zero.examples.multiplayer.rpc.DisconnectRequest(first, "trace-disconnect")).orThrow();
            PlayerSession second = players.reconnect("alice", "token-alice", "trace-reconnect");
            System.out.println("registered=alice, firstSession=" + first.sessionId()
                    + ", reconnectedSession=" + second.sessionId()
                    + ", character=" + players.loadCharacter(second.uid()).orElseThrow().characterName());
        }
    }
}
