package __PACKAGE__;

import __PACKAGE__.generated.dto.GameLoginProtocolDTO;
import __PACKAGE__.generated.dto.codec.GameLoginProtocolDTOCodec;
import __PACKAGE__.generated.protocol.ProtocolIds;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.log.LogRuntime;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Minimal TCP client used by the scaffold to prove the listener path end to end.
 *
 * <p>The client speaks the same length-prefixed zero binary frames as the listener, sends one
 * generated login protocol request and reads the business response. It is deliberately small:
 * tests, the {@code --once} smoke flag and application startup checks can reuse it, while
 * production clients belong to the game client project.</p>
 *
 * @author zn
 */
public final class __CLIENT_CLASS__ {

    /**
     * Connect and read timeout.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /**
     * Demo account used by the local smoke request.
     */
    private static final String SMOKE_ACCOUNT = "tcp-smoke";

    /**
     * Demo token used by the local smoke request.
     */
    private static final String SMOKE_TOKEN = "tcp-smoke-token";

    private __CLIENT_CLASS__() {
    }

    /**
     * Sends one generated login request to the scaffold listener and validates the response.
     *
     * @param bindAddress listener address in {@code host:port} form; never null.
     * @param runtime running runtime used to read the resolved business identity and log items.
     * @return smoke result; never null; immutable; thread-safe.
     * @throws Exception when the socket cannot connect, times out or returns an unexpected frame.
     */
    public static SmokeResult smoke(final String bindAddress, final GameRuntime runtime)
            throws Exception {
        java.util.Objects.requireNonNull(bindAddress, "bindAddress");
        java.util.Objects.requireNonNull(runtime, "runtime");
        ProtocolFrameCodec codec = new ZeroBinaryFrameCodec();
        ProtocolFrame response = exchange(bindAddress, codec, loginFrame());
        if (response.protocolId() != ProtocolIds.GAME_LOGIN_PROTOCOL) {
            throw new IllegalStateException("unexpected response protocol: " + response.protocolId());
        }
        if ((response.flags() & 1) != 0) {
            throw new IllegalStateException("listener reported a dispatch failure: "
                    + new String(response.payload(), java.nio.charset.StandardCharsets.UTF_8));
        }
        ZeroReader reader = new ZeroReader(response.payload());
        GameLoginProtocolDTO login = GameLoginProtocolDTOCodec.INSTANCE.read(reader);
        String mode = runtime.require(group.zn.zero.runtime.bootstrap.RuntimeBasics.CONFIG)
                .getOrDefault(group.zn.zero.runtime.bootstrap.ZeroRuntimeConfigKeys.ZERO_MODE, "unknown");
        String name = runtime.require(group.zn.zero.runtime.bootstrap.RuntimeBasics.CONFIG)
                .getOrDefault(group.zn.zero.runtime.bootstrap.ZeroRuntimeConfigKeys.ZERO_NAME, "unknown");
        return new SmokeResult(
                response.protocolId(),
                1001L,
                login.traceId,
                login.accountId,
                mode,
                name);
    }

    /**
     * Sends the request frame and decodes the response frame.
     *
     * @param bindAddress listener address in {@code host:port} form.
     * @param codec frame codec shared with the listener.
     * @param request request frame.
     * @return decoded response frame; never null.
     * @throws Exception when the socket fails, the timeout expires or the response is truncated.
     */
    public static ProtocolFrame exchange(final String bindAddress, final ProtocolFrameCodec codec,
            final ProtocolFrame request) throws Exception {
        int separator = bindAddress.lastIndexOf(':');
        if (separator <= 0) {
            throw new IllegalArgumentException("bindAddress must be host:port: " + bindAddress);
        }
        String host = bindAddress.substring(0, separator);
        if (host.equals("0.0.0.0") || host.equals("::")) {
            host = "127.0.0.1";
        }
        int port = Integer.parseInt(bindAddress.substring(separator + 1));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) TIMEOUT.toMillis());
            socket.setSoTimeout((int) TIMEOUT.toMillis());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            byte[] requestBytes = codec.encode(request);
            output.writeInt(requestBytes.length);
            output.write(requestBytes);
            output.flush();
            DataInputStream input = new DataInputStream(socket.getInputStream());
            int responseLength = input.readInt();
            byte[] responseBytes = input.readNBytes(responseLength);
            if (responseBytes.length != responseLength) {
                throw new IllegalStateException("incomplete response frame");
            }
            return codec.decode(responseBytes);
        }
    }

    private static ProtocolFrame loginFrame() {
        GameLoginProtocolDTO request = new GameLoginProtocolDTO();
        request.accountId = SMOKE_ACCOUNT;
        request.token = SMOKE_TOKEN;
        request.traceId = "trace-tcp-smoke";
        try (group.zn.zero.protocol.buffer.ZeroWriter writer = new group.zn.zero.protocol.buffer.ZeroWriter()) {
            GameLoginProtocolDTOCodec.INSTANCE.write(writer, request);
            return new ProtocolFrame(ProtocolIds.GAME_LOGIN_PROTOCOL, 1, 0, null, writer.toByteArray());
        }
    }

    /**
     * Result of the local TCP smoke request.
     *
     * @param protocolId response protocol identifier.
     * @param uid player identifier resolved by business code.
     * @param traceId trace identifier carried through the request.
     * @param accountId account identifier carried through the request.
     * @param mode runtime mode.
     * @param name runtime name.
     * @author zn
     */
    public record SmokeResult(int protocolId, long uid, String traceId, String accountId,
            String mode, String name) {

        /**
         * Returns a one-line machine-readable summary.
         *
         * @return summary line; never null; no data mutation; thread-safe.
         */
        public String summaryLine() {
            return "local-game-tcp=ok|protocol=" + protocolId + "|uid=" + uid
                    + "|trace=" + traceId + "|account=" + accountId
                    + "|mode=" + mode + "|name=" + name;
        }
    }
}
