# zeroServer 网络完整链路说明

本文档说明阶段 2B 当前从协议生成到网络接入、业务分发和 session 管理的完整链路。该链路用于固定模块边界和使用方式，不代表生产级登录、鉴权、限流或 GM API 已完成。

## 1. 模块边界

```text
.si / protoId.txt
  -> zero-codegen
  -> generated DTO / codec / ProtocolIds / dispatcher / BO
  -> zero-protocol ProtocolFrame / ZeroBinaryFrameCodec
  -> zero-net IServer / IConnection / ServerFrameHandler
  -> zero-logic LogicSessionManager / generated BO implementation
```

当前职责划分：

- `zero-codegen` 只负责生成协议产物，不参与服务器运行时。
- `zero-protocol` 负责协议定义、payload codec、frame codec 和二进制 reader/writer，不依赖 Netty。
- `zero-net` 只负责传输层 server、connection、frame 收发和 HTTP 最小请求响应，不保存玩家、账号或场景业务 session。
- `zero-logic` 承载业务示例、业务 session 示例和跨模块 smoke flow，可依赖 `zero-net`、`zero-protocol` 和 test scope 的 `zero-codegen`。

## 2. 当前完整请求流程

1. 使用 `.si` 和 `protoId.txt` 定义协议。
2. `ProtocolCodegenRunner` 生成 Java DTO、payload codec、`ProtocolIds`、`XXXEventBO` 和 `GeneratedProtocolDispatcher`。
3. 客户端或测试侧用生成 codec 把 DTO 写成 payload。
4. payload 被封装为 `ProtocolFrame`。
5. TCP 场景下，`NettyTcpServer` 外层使用 4 字节长度字段处理粘包/拆包，内部用 `ZeroBinaryFrameCodec` 编解码 `ProtocolFrame`。
6. `ServerFrameHandler` 在业务 executor 中执行，不在 Netty IO 线程中跑业务逻辑。
7. handler 使用 `LogicSessionManager.open(connection)` 创建或获取业务 session，记录请求次数、最近请求时间、渠道等属性。
8. handler 调用 `GeneratedProtocolDispatcher.dispatch(protocolId, payload)`。
9. dispatcher 解码 payload 并调用已注册的 `XXXEventBO`。
10. handler 根据业务处理结果编码响应 `ProtocolFrame` 并通过 `IConnection.sendFrame` 写回。
11. TCP 连接关闭时通过 `ConnectionListener.onClose` 清理业务 session。

## 3. 切换与使用说明

### 从旧网络 API 切换

- `NetServer` 已迁移为 `IServer`。
- `NetServerOptions` 已迁移为 `ServerOptions`。
- `TransportType` 已迁移为 `ServerType`。
- `NetCodecType` 已迁移为 `ServerCodecType`。
- `NetFrameHandler` 已迁移为 `ServerFrameHandler`。
- `zero-net.session.*` 不再承载业务 session；业务 session 示例位于 `zero-logic.session`。

### 生成物放置规则

生成的 dispatcher 会 import 生成 BO 接口和 DTO codec。因此：

- 不要把业务协议 dispatcher 放进框架核心 `zero-net` 模块，否则会让 `zero-net` 反向依赖业务 BO。
- 推荐把 DTO、codec、ProtocolIds 放在协议模块或业务协议包。
- 推荐把 BO、BOImp、dispatcher 放在业务模块、游戏服装配模块或示例 `zero-logic` 中。
- 如果要拆分输出目录，可使用 `--outJavaDto`、`--outJavaProtocol`、`--outJavaBo`、`--outJavaDispatcher` 和对应 `--java*Pkg` 参数，但必须检查依赖方向。

### 传输类型切换

- TCP：当前核心主线，适合长连接请求；外层 4 字节长度字段只属于 `zero-net` TCP 包帧，不改变 `zero-protocol` frame 格式。
- UDP：当前为一报一帧的最小无连接语义，`NettyUdpConnection` 是单次远端地址上下文，不代表可靠业务 session。
- HTTP：当前是 Netty `codec-http` 最小请求/响应模型，不提供生产 REST 路由、GM 鉴权或审计。
- KCP：当前保留 fail-fast 边界；真实实现需基于 `java-Kcp` 与 KCP/TCP 协作登录方案提交独立 Design Proposal。
- WebSocket / JSON / Protobuf：当前只保留 `ServerType` / `ServerCodecType` 扩展口，未实现具体 server 或 codec。

### session 使用边界

- `IConnection.attributes()` 只放连接级、传输层临时属性。
- `LogicSession` 才是业务 session 示例，可记录请求频率、请求间隔、渠道编码等业务相关信息。
- TCP session 可按连接生命周期打开和关闭。
- UDP 没有真实连接生命周期，不能直接套用 TCP session 语义。
- KCP 后续应基于 conv、token、UDP 地址映射和 TCP 回退策略建立独立 session 绑定规则。

## 4. 当前验证

`zero-logic` 新增 `CodegenProtocolNetSessionFlowTest`，覆盖：

- 临时 `.si` / `protoId.txt`。
- `ProtocolCodegenRunner` 生成 Java 协议产物。
- 动态编译并加载生成 DTO、codec、BO、dispatcher。
- 生成 codec 编码 payload。
- `ZeroBinaryFrameCodec` 编解码 `ProtocolFrame`。
- `NettyTcpServer` 启动、收包、回包和关闭。
- `LogicSessionManager` 记录渠道与请求计数。
- `GeneratedProtocolDispatcher` 调用生成 BO。

## 5. 后续风险

- 统一线程管理尚未落地，当前 Netty server 内部创建 IO 线程组，业务 executor 由调用方传入。
- 生成 dispatcher 当前同步调用 BO，响应生成仍由 handler 或业务层负责。
- 真实 KCP、WebSocket、JSON/Protobuf 和生产 HTTP 路由都需要独立 Design Proposal 与针对性验证。
- 如果后续修改 dispatcher 返回值、异步语义或协议线格式，属于高风险协议契约变更，必须单独确认。

## 6. 显式启用生产 TCP 生命周期

local/prototype 路径保持原有语义：未传入 `ProductionNetworkLifecycle` 时，TCP 连接建立后立即触发 `ConnectionListener.onOpen`，首个 `ProtocolFrame` 直接投递业务 executor。生产路径必须同时满足以下条件：

1. 配置 `zero.net.lifecycle.enabled=true`。
2. 对 `ZeroProductionRuntimeBuilder` 显式调用 `networkPolicy(...)`；需要自定义限流时再调用 `networkRateLimiter(...)`。
3. builder 必须使用 remote IO 不会内联的 `ZeroRuntimeExecutors`，否则在构建组件图前 fail-fast。
4. 从已构建 runtime 调用 `require(NetworkRuntime.NETWORK_LIFECYCLE)` 取得生命周期组合；该键位于 `zero-runtime-net`。
5. 调用带 `ProductionNetworkLifecycle` 参数的 `ServerFactory.tcp(...)` 或 `NettyTcpServer` 构造。

显式启用后的连接流程：

```text
ACCEPTED
  -> HANDSHAKING（首帧只做有界、非阻塞校验）
  -> AUTHENTICATING（投递到受管 remote IO executor）
  -> 重连协调端口（需要修改玩家状态时只发 player actor 消息）
  -> ESTABLISHED
  -> 业务 executor / generated dispatcher
```

握手、鉴权、心跳、队列预算或限流失败时，连接绑定 `NetErrorCode`、发出 observer 事件并关闭；首轮实现不会新增客户端错误响应 frame，因此不改变现有协议线格式。默认握手/鉴权超时分别为 5 秒和 10 秒，心跳间隔 15 秒、允许丢失 2 次，重连窗口 30 秒，单连接入站 frame 预算 1024；这些值均可配置，正式部署必须按游戏类型和容量测试覆盖。

当前切片只覆盖 TCP 最小治理，不包含真实账号/token 鉴权、TLS、WAF、DDoS 防护、完整网关、UDP/KCP/WebSocket 生命周期或生产容量承诺。
