package __PACKAGE__;

import __PACKAGE__.generated.bo.GameEnterSceneEventBO;
import __PACKAGE__.generated.bo.GameLoginEventBO;
import __PACKAGE__.generated.bo.GameMoveEventBO;
import __PACKAGE__.generated.protocol.dispatch.GeneratedProtocolDispatcher;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.log.LogAppender;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.runtime.api.GameRuntime;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Protocol flow helper kept outside the composition root. */
public final class LocalGameFlow {
    private final GeneratedProtocolDispatcher dispatcher;

    /** Creates a flow around an injected dispatcher. */
    public LocalGameFlow(final GeneratedProtocolDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    /** Dispatches a request through the generated codec without waiting for business completion. */
    public <T> CompletionStage<Boolean> dispatch(final int protocolId, final ZeroPayloadCodec<T> codec, final T request) {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(request, "request");
        try (ZeroWriter writer = new ZeroWriter()) {
            codec.write(writer, request);
            return java.util.concurrent.CompletableFuture.completedFuture(
                    dispatcher.dispatch(protocolId, writer.toByteArray()));
        }
    }

    /**
     * Builds the scaffold runtime graph over an existing safety-wrapped log port.
     *
     * <p>Only this class and {@link __APP_CLASS__} touch the generated {@code RuntimeAssembly};
     * business code receives the {@link GameRuntime} facade or plain service ports. Callers own
     * the returned runtime: they must start it and later close it.</p>
     *
     * <p>Business code never sees the terminal log SPI: the generated composition root adapts this
     * {@link LogAppender} into the framework pipeline's final stage, so every record still passes
     * structure validation and redaction before the caller's sink observes it.</p>
     *
     * @param config framework configuration; never null.
     * @param logAppender safety-wrapped log port; never null.
     * @return unstarted runtime; never null; not thread-safe to construct concurrently.
     */
    public static GameRuntime createRuntime(final ZeroConfig config, final LogAppender logAppender) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(logAppender, "logAppender");
        return RuntimeAssembly.create(config, logAppender::append);
    }

    /**
     * Registers every generated business event BO on a new dispatcher.
     *
     * <p>The dispatcher maps protocol IDs to handwritten business handlers. Registering all
     * three generated interfaces keeps the network entry point, the smoke flow and the tests on
     * one code path.</p>
     *
     * @param business handwritten BO implementation; never null.
     * @return dispatcher with all scaffold protocols registered; never null.
     */
    public static GeneratedProtocolDispatcher dispatcher(
            final GameLoginEventBO business,
            final GameEnterSceneEventBO enterSceneBusiness,
            final GameMoveEventBO moveBusiness) {
        Objects.requireNonNull(business, "business");
        Objects.requireNonNull(enterSceneBusiness, "enterSceneBusiness");
        Objects.requireNonNull(moveBusiness, "moveBusiness");
        GeneratedProtocolDispatcher generated = new GeneratedProtocolDispatcher();
        generated.registerGameLoginEventBO(business);
        generated.registerGameEnterSceneEventBO(enterSceneBusiness);
        generated.registerGameMoveEventBO(moveBusiness);
        return generated;
    }
}
