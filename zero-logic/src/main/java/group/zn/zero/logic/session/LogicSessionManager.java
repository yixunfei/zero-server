package group.zn.zero.logic.session;

import group.zn.zero.net.IConnection;
import group.zn.zero.net.ConnectionListener;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;
import group.zn.zero.core.error.ZeroException;

/**
 * 业务逻辑示例会话管理器。
 *
 * @author zn
 */
public final class LogicSessionManager implements ConnectionListener {

    /**
     * 按连接标识保存会话。
     */
    private final Map<String, LogicSession> sessionsByConnection = new HashMap<>();
    /** 目录容量，由实例监视器保护准入和释放。 */
    private final int capacity;

    /** 创建默认最多 65536 个会话的目录，不创建线程。 */
    public LogicSessionManager() { this(65536); }
    /** @param capacity 正会话上限。 @throws IllegalArgumentException 非正容量。 */
    public LogicSessionManager(final int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("session capacity must be positive");
        this.capacity = capacity;
    }

    /**
     * 打开业务会话。
     *
     * @param connection 传输层连接；不可为空。
     * @return 业务会话；不可为空；线程安全。
     */
    public synchronized LogicSession open(final IConnection connection) {
        IConnection current = Objects.requireNonNull(connection, "connection");
        String id = Objects.requireNonNull(current.connectionId(), "connectionId");
        LogicSession existing = sessionsByConnection.get(id);
        if (existing != null) {
            if (existing.connection() != current) throw failure(LogicSessionErrorCode.CONNECTION_CONFLICT);
            return existing;
        }
        if (sessionsByConnection.size() >= capacity) throw failure(LogicSessionErrorCode.CAPACITY_EXCEEDED);
        LogicSession session = new LogicSession(UUID.randomUUID().toString(), current);
        session.attributes().put(StandardLogicSessionAttributes.REQUEST_COUNT, 0L);
        sessionsByConnection.put(id, session);
        return session;
    }

    /**
     * 关闭业务会话。
     *
     * @param connection 传输层连接；不可为空。
     * @return 被关闭的会话；不可为空；可能为空；线程安全。
     */
    public synchronized Optional<LogicSession> close(final IConnection connection) {
        Objects.requireNonNull(connection, "connection");
        LogicSession session = sessionsByConnection.get(connection.connectionId());
        if (session == null || session.connection() != connection) return Optional.empty();
        session.close();
        sessionsByConnection.remove(connection.connectionId());
        return Optional.of(session);
    }

    /** 传输连接关闭时按连接对象身份回收业务会话；重复或迟到回调安全忽略。 */
    @Override public void onClose(final IConnection connection) { close(connection); }

    /** 连接异常同样结束业务会话；异常本身由网络层日志与观察器处理。 */
    @Override public void onException(final IConnection connection, final Throwable cause) {
        if (connection != null) close(connection);
    }

    /**
     * 按连接标识查找会话。
     *
     * @param connectionId 连接标识；不可为空。
     * @return 业务会话；不可为空；可能为空；线程安全。
     */
    public synchronized Optional<LogicSession> findByConnectionId(final String connectionId) {
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
        session.markRequest();
    }

    /**
     * 返回会话快照。
     *
     * @return 不可变、无序、可能为空、线程安全的会话列表。
     */
    public synchronized List<LogicSession> sessions() {
        return List.copyOf(sessionsByConnection.values());
    }
    private static ZeroException failure(final LogicSessionErrorCode code) {
        return ZeroException.of(code, code.message(), null);
    }
}
