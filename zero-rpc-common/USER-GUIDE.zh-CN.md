# zero-rpc-common 用户注意事项与使用说明

`zero-rpc-common` 是业务双端共享的 RPC 契约模块，只提供最小 common API：

- `@RpcService`：声明 RPC 服务名、版本、默认 topic、默认 consumer group。
- `@RpcMethod`：声明服务内稳定 methodId、方法名、超时、调用模式、幂等标记、partitionKey。
- `RpcCallMode`：声明 request/response 或 oneway。
- `RpcResult<T>`：统一成功/失败结果，失败必须绑定 `ErrorCode`。

本模块不负责传输、编解码、Kafka、服务发现、线程调度和业务实现。业务方应把接口 DTO 与 RPC common 接口放到可被调用方和服务方共同依赖的契约包中。

## 一、模块边界

推荐依赖方向：

```text
业务调用方 -> zero-rpc-common 契约模块
业务服务方 -> zero-rpc-common 契约模块
zero-rpc-runtime -> zero-rpc-common
zero-rpc-kafka -> zero-rpc-runtime
```

注意事项：

- common 接口不要依赖 `zero-rpc-kafka`、Kafka、Nacos、Redis、MongoDB 等具体中间件。
- common 接口不要直接暴露 `RpcRequest`、`RpcResponse`、`byte[]`。
- common 接口方法当前只支持 `RpcResult<T>` 或 `CompletionStage<RpcResult<T>>`。
- `T` 当前必须是具体 Class；复杂泛型请封装成 DTO，例如 `PlayerListResponseDTO`。
- methodId 必须稳定，发布后不要随意复用或改号。
- version 必须稳定递增，破坏性变更应新建版本。
- 对外失败必须返回带 `ErrorCode` 的 `RpcResult.failure(...)`，不要吞异常。

## 二、标准接口定义

示例：

```java
package group.zn.zero.example.rpc.common;

import group.zn.zero.rpc.common.RpcCallMode;
import group.zn.zero.rpc.common.RpcMethod;
import group.zn.zero.rpc.common.RpcResult;
import group.zn.zero.rpc.common.RpcService;
import java.util.concurrent.CompletionStage;

/**
 * 玩家远程 RPC 契约。
 *
 * @author zn
 */
@RpcService(
        name = "player.remote",
        version = 1,
        topic = "player.rpc.request",
        group = "player-rpc-provider",
        description = "玩家跨服查询与通知 RPC")
public interface PlayerRemoteRpc {

    /**
     * 查询玩家概要。
     *
     * @param request 查询请求；不可为 null；线程安全性由调用方 DTO 使用方式决定。
     * @return RPC 查询结果；不可为 null；失败时绑定 ErrorCode；线程安全。
     */
    @RpcMethod(
            id = 1001,
            timeoutMillis = 3000,
            idempotent = true,
            partitionKey = "arg0.playerId",
            description = "按玩家 ID 查询玩家概要")
    RpcResult<PlayerProfileDTO> queryPlayer(PlayerQueryDTO request);

    /**
     * 异步查询玩家概要。
     *
     * @param request 查询请求；不可为 null。
     * @return 异步 RPC 查询结果；不可为 null；CompletionStage 本身可跨线程完成。
     */
    @RpcMethod(
            id = 1002,
            timeoutMillis = 3000,
            idempotent = true,
            partitionKey = "arg0.playerId",
            description = "异步按玩家 ID 查询玩家概要")
    CompletionStage<RpcResult<PlayerProfileDTO>> queryPlayerAsync(PlayerQueryDTO request);

    /**
     * 通知玩家被踢下线。
     *
     * @param request 踢下线请求；不可为 null。
     * @return oneway 发送结果；不可为 null；只代表发送完成，不代表远端业务已经成功。
     */
    @RpcMethod(
            id = 1003,
            mode = RpcCallMode.ONEWAY,
            timeoutMillis = 1000,
            idempotent = false,
            partitionKey = "arg0.playerId",
            description = "发送玩家踢下线通知")
    RpcResult<Void> kickPlayer(PlayerKickDTO request);
}
```

## 三、DTO 定义建议

DTO 建议保持简单、可被协议 codegen 生成 codec，并避免暴露可变集合内部状态。

```java
package group.zn.zero.example.rpc.common;

/**
 * 玩家查询请求。
 *
 * @author zn
 */
public final class PlayerQueryDTO {

    /**
     * 玩家 ID。
     */
    private long playerId;

    /**
     * 创建空请求，用于协议反序列化。
     */
    public PlayerQueryDTO() {
    }

    /**
     * 创建玩家查询请求。
     *
     * @param playerId 玩家 ID。
     */
    public PlayerQueryDTO(final long playerId) {
        this.playerId = playerId;
    }

    /**
     * 返回玩家 ID。
     *
     * @return 玩家 ID；线程安全。
     */
    public long playerId() {
        return playerId;
    }
}

/**
 * 玩家概要响应。
 *
 * @author zn
 */
public final class PlayerProfileDTO {

    /**
     * 玩家 ID。
     */
    private long playerId;

    /**
     * 玩家名称。
     */
    private String name = "";

    /**
     * 创建空响应，用于协议反序列化。
     */
    public PlayerProfileDTO() {
    }

    /**
     * 创建玩家概要响应。
     *
     * @param playerId 玩家 ID。
     * @param name 玩家名称；不可为 null。
     */
    public PlayerProfileDTO(final long playerId, final String name) {
        this.playerId = playerId;
        this.name = java.util.Objects.requireNonNull(name, "name");
    }

    /**
     * 返回玩家 ID。
     *
     * @return 玩家 ID；线程安全。
     */
    public long playerId() {
        return playerId;
    }

    /**
     * 返回玩家名称。
     *
     * @return 玩家名称；不可为 null；线程安全。
     */
    public String name() {
        return name;
    }
}

/**
 * 玩家踢下线请求。
 *
 * @author zn
 */
public final class PlayerKickDTO {

    /**
     * 玩家 ID。
     */
    private long playerId;

    /**
     * 原因。
     */
    private String reason = "";

    /**
     * 创建空请求，用于协议反序列化。
     */
    public PlayerKickDTO() {
    }

    /**
     * 创建踢下线请求。
     *
     * @param playerId 玩家 ID。
     * @param reason 原因；不可为 null。
     */
    public PlayerKickDTO(final long playerId, final String reason) {
        this.playerId = playerId;
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    /**
     * 返回玩家 ID。
     *
     * @return 玩家 ID；线程安全。
     */
    public long playerId() {
        return playerId;
    }

    /**
     * 返回原因。
     *
     * @return 原因；不可为 null；线程安全。
     */
    public String reason() {
        return reason;
    }
}
```

## 四、服务实现侧返回约定

服务实现不要直接抛业务错误给调用方，应优先返回 `RpcResult.failure(...)`。确实抛出的 `ZeroException` 会被运行时转换为 RPC 错误响应。

```java
package group.zn.zero.example.rpc.server;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.example.rpc.common.PlayerKickDTO;
import group.zn.zero.example.rpc.common.PlayerProfileDTO;
import group.zn.zero.example.rpc.common.PlayerQueryDTO;
import group.zn.zero.example.rpc.common.PlayerRemoteRpc;
import group.zn.zero.rpc.common.RpcResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 玩家远程 RPC 实现。
 *
 * @author zn
 */
public final class PlayerRemoteRpcImpl implements PlayerRemoteRpc {

    /**
     * 查询玩家概要。
     *
     * @param request 查询请求；不可为 null。
     * @return 查询结果；不可为 null；失败时绑定 ErrorCode。
     */
    @Override
    public RpcResult<PlayerProfileDTO> queryPlayer(final PlayerQueryDTO request) {
        if (request.playerId() <= 0L) {
            return RpcResult.failure(SystemErrorCode.INVALID_ARGUMENT, "playerId must be positive");
        }
        return RpcResult.success(new PlayerProfileDTO(request.playerId(), "player-" + request.playerId()));
    }

    /**
     * 异步查询玩家概要。
     *
     * @param request 查询请求；不可为 null。
     * @return 异步查询结果；不可为 null。
     */
    @Override
    public CompletionStage<RpcResult<PlayerProfileDTO>> queryPlayerAsync(final PlayerQueryDTO request) {
        return CompletableFuture.completedFuture(queryPlayer(request));
    }

    /**
     * 通知玩家踢下线。
     *
     * @param request 踢下线请求；不可为 null。
     * @return oneway 业务结果；不可为 null。
     */
    @Override
    public RpcResult<Void> kickPlayer(final PlayerKickDTO request) {
        if (request.playerId() <= 0L) {
            return RpcResult.failure(SystemErrorCode.INVALID_ARGUMENT, "playerId must be positive");
        }
        return RpcResult.success(null);
    }
}
```

## 五、partitionKey 使用说明

`@RpcMethod.partitionKey` 用于 Kafka message key 或其他传输层分区键。当前运行时支持以下形式：

- `""`：空字符串，传输层通常回退到 `correlationId`。
- `"playerId"`：读取第一个参数的 `playerId` 字段、`playerId()`、`getPlayerId()` 或 `isPlayerId()`。
- `"arg0.playerId"`：读取第一个参数的 `playerId`。
- `"arg1.sceneId"`：读取第二个参数的 `sceneId`。
- `"arg0"`：直接将第一个参数转为字符串。

注意：

- 分区键访问器在代理创建阶段编译并缓存，调用热路径只执行读取。
- 如果路径访问到 null，结果为空字符串。
- 对私有字段或私有方法的访问依赖 `MethodHandles.privateLookupIn`，在强模块化或安全限制环境下可能失败。
- 分区键应选择玩家 ID、场景 ID、房间 ID 等天然路由键，避免使用高基数字符串拼接导致热点或不可控分区。

## 六、风险与缓解

### methodId 变更风险

风险：methodId 是服务内稳定路由键，修改后调用方与服务方会路由不一致。

缓解：

- 新增方法使用新 id。
- 破坏性调整提升 `@RpcService.version`。
- 保留旧接口到迁移完成。

### version 变更风险

风险：服务名实际路由会包含版本，例如 `player.remote:v1`。调用方和服务方版本不一致会找不到 handler。

缓解：

- 灰度期间同时注册 v1 和 v2。
- 文档中明确接口迁移窗口。
- 避免在同一 version 内改变 DTO wire 结构语义。

### oneway 误用风险

风险：oneway 只保证发送完成，不保证远端执行业务成功。

缓解：

- 只用于通知、日志、低价值异步操作。
- 关键操作使用 request/response。
- 业务需要回执时使用异步回调事件或另一个 RPC 通知。

### null 值风险

风险：当前 runtime 不支持非 `Void` 参数或结果为 null，codec 会失败。

缓解：

- 用空 DTO 表示无参数。
- 用 `RpcResult<Void>` 表示无结果。
- 可选字段放在 DTO 内，并由协议 codec 定义默认值。

### 泛型结果风险

风险：当前只支持 `RpcResult<T>` 中 `T` 是具体 Class，不支持 `RpcResult<List<PlayerDTO>>`。

缓解：

- 使用 `PlayerListResponseDTO` 包装集合。
- 在 DTO 注释中说明集合是否可变、有序、可为空、线程安全。

## 七、场景示例附录

### 场景 1：查询类 request/response

```java
@RpcMethod(id = 1001, timeoutMillis = 3000, idempotent = true, partitionKey = "arg0.playerId")
RpcResult<PlayerProfileDTO> queryPlayer(PlayerQueryDTO request);
```

适用：查询玩家、查询房间、查询跨服状态。

特别注意：查询也可能读远端缓存或数据库，不要在 Actor 线程中同步等待太久。

替代方案：对高频查询优先使用本地缓存、订阅同步或异步事件。

### 场景 2：异步 request/response

```java
@RpcMethod(id = 1002, timeoutMillis = 3000, idempotent = true, partitionKey = "arg0.playerId")
CompletionStage<RpcResult<PlayerProfileDTO>> queryPlayerAsync(PlayerQueryDTO request);
```

适用：调用方不希望阻塞当前线程，尤其是 IO 线程、Actor 线程或批量聚合查询。

特别注意：`CompletionStage` 完成线程由传输实现决定，回调中不要直接修改非线程安全状态。

替代方案：回调中投递 Actor 消息，由绑定线程修改玩家或场景状态。

### 场景 3：oneway 通知

```java
@RpcMethod(id = 1003, mode = RpcCallMode.ONEWAY, timeoutMillis = 1000, partitionKey = "arg0.playerId")
RpcResult<Void> kickPlayer(PlayerKickDTO request);
```

适用：踢人通知、刷新通知、异步日志、非关键后台通知。

特别注意：调用方成功只代表发送完成，不代表远端执行业务成功。

替代方案：关键业务使用 request/response，或使用消息事件加业务回执。

### 场景 4：业务失败返回

```java
public RpcResult<PlayerProfileDTO> queryPlayer(final PlayerQueryDTO request) {
    if (request.playerId() <= 0L) {
        return RpcResult.failure(SystemErrorCode.INVALID_ARGUMENT, "playerId must be positive");
    }
    return RpcResult.success(new PlayerProfileDTO(request.playerId(), "player-" + request.playerId()));
}
```

适用：参数错误、玩家不存在、权限不足、业务状态不允许。

特别注意：不要只打印日志后继续执行；失败必须绑定 `ErrorCode`。

替代方案：跨模块通用错误补充专门的 RPC 业务 `ErrorCode` 枚举。
