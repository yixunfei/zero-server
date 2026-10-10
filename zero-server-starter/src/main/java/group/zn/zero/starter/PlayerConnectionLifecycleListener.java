package group.zn.zero.starter;

import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.player.PlayerConnectionCloseCoordinator;
import group.zn.zero.player.PlayerLoginResult;
import group.zn.zero.player.PlayerLogoutRequest;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Binds successful login identities to transport connections and releases them on close. */
public final class PlayerConnectionLifecycleListener implements ConnectionListener {
    private static final System.Logger LOGGER = System.getLogger(PlayerConnectionLifecycleListener.class.getName());
    private final PlayerConnectionCloseCoordinator coordinator;
    private final Map<IConnection, PlayerLogoutRequest> bindings = new IdentityHashMap<>();

    /** Creates a starter-side network/player composition adapter. */
    public PlayerConnectionLifecycleListener(final PlayerConnectionCloseCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /** Associates the precise successful login result with its connection object. */
    public synchronized void bind(final IConnection connection, final PlayerLoginResult login) {
        bindings.put(Objects.requireNonNull(connection, "connection"), new PlayerLogoutRequest(
                login.accountId(), login.uid(), login.sessionId(), login.traceId()));
    }

    /** Removes and dispatches the exact login binding once. */
    @Override public void onClose(final IConnection connection) {
        PlayerLogoutRequest request;
        synchronized (this) { request = bindings.remove(connection); }
        if (request != null) {
            coordinator.onConnectionClosed(request).whenComplete((ignored, failure) -> {
                if (failure != null) LOGGER.log(System.Logger.Level.ERROR,
                        "player connection cleanup failed", failure);
            });
        }
    }

    /** Treats transport failure as connection closure and releases the same binding. */
    @Override public void onException(final IConnection connection, final Throwable cause) {
        if (connection != null) onClose(connection);
    }
}
