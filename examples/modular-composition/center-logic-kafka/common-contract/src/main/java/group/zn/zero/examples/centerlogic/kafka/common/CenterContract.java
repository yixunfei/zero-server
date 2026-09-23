package group.zn.zero.examples.centerlogic.kafka.common;

import group.zn.zero.rpc.common.RpcMethod;
import group.zn.zero.rpc.common.RpcResult;
import group.zn.zero.rpc.common.RpcService;

/** Shared wire-neutral contract used by independently launched center and logic JVMs. */
@RpcService(name = "onboarding.center", version = 1)
public interface CenterContract {
    /** Registers a logic instance. */
    @RpcMethod(id = 1, timeoutMillis = 3000, idempotent = true)
    RpcResult<CenterAck> register(CenterRegistration request);

    /** Sends a heartbeat. */
    @RpcMethod(id = 2, timeoutMillis = 3000, idempotent = true)
    RpcResult<CenterAck> heartbeat(CenterHeartbeat request);

    /** Removes a logic instance before drain/stop. */
    @RpcMethod(id = 3, timeoutMillis = 3000, idempotent = true)
    RpcResult<CenterAck> unregister(CenterRegistration request);
}
