package group.zn.zero.examples.multiplayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 中心服单一注册表与粘性路由；同步方法保证线程安全，不访问外部服务。 @author zn */
public final class CenterRouter {
    /** 按注册顺序保存实例；所有访问持本对象锁。 */
    private final List<String> logicServers = new ArrayList<>();
    /** UID 已分配路由，示例期间保持稳定；不实现迁服与健康剔除。 */
    private final Map<Long, String> assignments = new HashMap<>();
    /** 下次新 UID 分配的位置。 */
    private int cursor;

    /**
     * 初始化路由表，不创建连接；允许空表等待显式注册。
     * @param logicServers 有序实例列表；复制列表，不保留可变引用。
     * @throws NullPointerException 列表为空。
     * @throws MultiServerError 实例 ID 为空白。
     */
    public CenterRouter(final List<String> logicServers) {
        for (String instance : Objects.requireNonNull(logicServers, "logicServers")) {
            register(instance);
        }
    }

    /**
     * 幂等注册实例；线程安全，可能扩充路由表。
     * @param instanceId 非空实例 ID；示例未验证远端健康。
     * @return 当前不同实例数。
     * @throws MultiServerError 实例 ID 非法。
     */
    public synchronized int register(final String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new MultiServerError(MultiServerErrorCode.INVALID_REQUEST);
        }
        if (!logicServers.contains(instanceId)) {
            logicServers.add(instanceId);
        }
        return logicServers.size();
    }

    /**
     * 首次按轮询分配，后续复用 UID 路由；线程安全，可能新增路由绑定。
     * @param uid 正数玩家 ID。
     * @return 不可空逻辑服 ID。
     * @throws MultiServerError UID 非法或没有实例。
     */
    public synchronized String allocate(final long uid) {
        if (uid <= 0) {
            throw new MultiServerError(MultiServerErrorCode.INVALID_REQUEST);
        }
        if (logicServers.isEmpty()) {
            throw new MultiServerError(MultiServerErrorCode.NO_LOGIC_SERVER);
        }
        return assignments.computeIfAbsent(uid, ignored -> {
            String instance = logicServers.get(cursor);
            cursor = (cursor + 1) % logicServers.size();
            return instance;
        });
    }

    /** @return 不可变、有序、可能为空的实例快照；线程安全，不修改状态。 */
    public synchronized List<String> instances() { return List.copyOf(logicServers); }
}
