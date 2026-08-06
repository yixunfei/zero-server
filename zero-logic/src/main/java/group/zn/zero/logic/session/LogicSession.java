package group.zn.zero.logic.session;

import group.zn.zero.net.IConnection;
import java.util.Objects;

/**
 * 业务逻辑示例会话。
 *
 * <p>该类型用于阶段 2B 的业务形态示例，不替代后续正式 `zero-player` 或 `zero-game` session。
 *
 * @author zn
 */
public final class LogicSession {

    /**
     * 会话标识。
     */
    private final String sessionId;

    /**
     * 传输层连接。
     */
    private final IConnection connection;

    /**
     * 会话属性。
     */
    private final LogicSessionAttributes attributes;

    /**
     * 创建业务逻辑会话。
     *
     * @param sessionId 会话标识；不可为空。
     * @param connection 传输层连接；不可为空。
     */
    public LogicSession(final String sessionId, final IConnection connection) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.attributes = new DefaultLogicSessionAttributes();
    }

    /**
     * 返回会话标识。
     *
     * @return 会话标识；不可为空；线程安全。
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * 返回连接标识。
     *
     * @return 连接标识；不可为空；线程安全。
     */
    public String connectionId() {
        return connection.connectionId();
    }

    /**
     * 返回传输层连接。
     *
     * @return 连接；不可为空；线程安全。
     */
    public IConnection connection() {
        return connection;
    }

    /**
     * 返回会话属性。
     *
     * @return 会话属性；不可为空；线程安全。
     */
    public LogicSessionAttributes attributes() {
        return attributes;
    }
}
