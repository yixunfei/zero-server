package group.zn.zero.logic.session;

import group.zn.zero.net.IConnection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务逻辑示例会话管理器。
 *
 * @author zn
 */
public final class LogicSessionManager {

    /**
     * 按连接标识保存会话。
     */
    private final Map<String, LogicSession> sessionsByConnection = new ConcurrentHashMap<>();

    /**
     * 打开业务会话。
     *
     * @param connection 传输层连接；不可为空。
     * @return 业务会话；不可为空；线程安全。
     */
    public LogicSession open(final IConnection connection) {
        IConnection current = Objects.requireNonNull(connection, "connection");
        return sessionsByConnection.computeIfAbsent(current.connectionId(), ignored -> {
            LogicSession session = new LogicSession(UUID.randomUUID().toString(), current);
            session.attributes().put(StandardLogicSessionAttributes.REQUEST_COUNT, 0L);
            return session;
        });
    }

    /**
     * 关闭业务会话。
     *
     * @param connection 传输层连接；不可为空。
     * @return 被关闭的会话；不可为空；可能为空；线程安全。
     */
    public Optional<LogicSession> close(final IConnection connection) {
        Objects.requireNonNull(connection, "connection");
        return Optional.ofNullable(sessionsByConnection.remove(connection.connectionId()));
    }

    /**
     * 按连接标识查找会话。
     *
     * @param connectionId 连接标识；不可为空。
     * @return 业务会话；不可为空；可能为空；线程安全。
     */
    public Optional<LogicSession> findByConnectionId(final String connectionId) {
        Objects.requireNonNull(connectionId, "connectionId");
        return Optional.ofNullable(sessionsByConnection.get(connectionId));
    }

    /**
     * 标记一次请求。
     *
     * @param session 业务会话；不可为空。
     */
    public void markRequest(final LogicSession session) {
        Objects.requireNonNull(session, "session");
        long now = System.currentTimeMillis();
        session.attributes().update(
                StandardLogicSessionAttributes.REQUEST_COUNT,
                count -> count == null ? 1L : count + 1L);
        session.attributes()
                .put(StandardLogicSessionAttributes.LAST_REQUEST_TIME_MILLIS, now)
                .ifPresent(previous -> session.attributes().put(
                        StandardLogicSessionAttributes.LAST_REQUEST_INTERVAL_MILLIS,
                        Math.max(0L, now - previous)));
    }

    /**
     * 返回会话快照。
     *
     * @return 不可变、无序、可能为空、线程安全的会话列表。
     */
    public List<LogicSession> sessions() {
        return List.copyOf(sessionsByConnection.values());
    }
}
