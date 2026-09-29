# 2026-09-28 KCP 多场景优化迁移

适用于 `0.1.0-SNAPSHOT`、Java 21。此轮经确认直接调整开发阶段 API，不保留旧十参数构造器。
UDP 的 ZKCP v1 认证格式不变，新增的是 TLS 控制通道下发的 ZKCI v1 连接描述。

## 1. 配置与帧长

原 `new KcpOptions(mtu, interval, window, ...)` 改为明确场景和分组配置：

~~~java
var options = KcpOptions.builder(KcpProfile.MOBILE).build();
// 按需用 tuning(KcpTuning)、limits(KcpLimits)、timeouts(KcpTimeouts) 覆盖。
// 从当前配置派生时使用 options.toBuilder()，不要再复制十个位置参数。
~~~

- `defaults()` 现在等同 BALANCED；其最大完整帧从旧的算法上界 **150528** 字节改为 **32768** 字节。
  包含 ZeroBinary 头部，不能发送 32768 字节 payload 后再追加协议头。
- LOW_LATENCY / BALANCED / MOBILE / LOW_FREQUENCY / BULK 分别为 8 / 32 / 32 / 16 / 128 KiB 完整帧。
- 实际服务帧上限还受 `ServerOptions.maxFrameLength` 限制，连接描述下发二者较小的有效值。
  扩大业务帧时同时检查网络帧长、接收窗口、MTU、段数、每连接及全局字节预算。
- 发送、接收窗口分别由 `tuning().sendWindow() / receiveWindow()` 表达；`window()` 返回接收窗口。
- TLS 和拥塞控制仍默认开启。预设只是起点，参数详见[场景指南](../guides/kcp-scenarios.zh-CN.md)。

## 2. 连接时限

`KcpTimeouts` 独立表达绑定、心跳、空闲、发送无进展、绝对票据时限。
原先只覆盖 idle/ticket 的调用方须重新确认这些时限，尤其是移动网络和低频连接。

- 默认建链期限 10 秒、票据最长 30 分钟；实际授权还受 TCP 身份有效期限制。
- 心跳必须短于空闲时限；绑定期限不超过空闲时限；空闲/无进展期限不超过票据时限。
- 有效心跳可以维持空闲连接，但不能延长身份、票据或无 ACK 进展时限。
- 安静会话仍最多每 250ms 复查控制连接授权；收到业务数据时立即重新检查。

## 3. TLS 控制面和 Java 客户端

应用登录协议原有 ID 不变，由业务新增/修改票据响应的内容：

1. TLS TCP 完成框架身份认证和 ESTABLISHED 后调用
   `server.issueConnectInfo(control, publicHost, publicUdpPort)`。
2. 将 `info.encode()` 仅通过该 TLS 通道下发；字节含秘密，不记录到日志、指标或普通配置。
3. Java 客户端用 `KcpConnectInfo.decode(payload)` 获取端点、票据和权威参数，交给 `KcpClient`。
4. 用 `connect()` 等待认证 hello 成功，随后调用 `sendFrame`；应用无需自行 pump、更新算法或定时发心跳。
5. 应用先等待客户端关闭，再关闭其业务执行器/共享 IO。自有 IO 随客户端关闭，借用 IO 保留给组合根。

ZKCI v1 严格验证版本、稳定 profile wireId、参数和长度（最多 512 字节）；旧自定义票据载荷需同步迁移。
ZKCP UDP 外层未改。跨语言实现应遵循[协议契约](../reference/kcp-transport-contract.zh-CN.md)，
可用 `zero-net-kcp/src/test/resources/zkci-v1.hex` 检查固定字节向量。

创建客户端可能进行 DNS 解析，须在适合阻塞解析的组合根/连接流程中执行，不能放在 Netty IO 或业务 Actor 内。
使用多个客户端时显式借用 `NettyIoResources`，避免每个实例拥有一套 IO 线程。

## 4. 恢复与 TCP 回退

实现 `KcpControlPlane.acquire/revoke` 对接项目的 TLS 协议，再由 `KcpRecovery` 管理恢复。
应用仍负责登录、控制面超时、事务幂等和恢复业务快照。

- `reconnect()` 关闭旧客户端、撤销旧票据，再领新票据与握手；恢复期间新发送直接失败。
- `fallbackToTcp()` 完成撤销屏障后，应用才提交后续 TCP 请求。
- `send/sendFrame` 成功仍只表示本地入队，不是远端 ACK 或业务成功。
- 未确认请求不会自动重放；关闭传输和撤销屏障不取消已进入业务 handler 的事务。
- 地址变化必须重领票据，不能在原票据上修改来源 IP/端口；敏感业务使用 TLS TCP。

## 5. 可选 runtime 装配与监控

应用可以直接依赖 `zero-net-kcp`，或新增由 BOM 管理的 `group.zn.zero:zero-runtime-kcp`。
默认 starter、普通网络模块及 core 不会因此强制启动 KCP。

`RuntimeComposition` 和 `ProductionAssembly` 均可直接
`install(KcpRuntime.module("game", host, port, profile, handler))`，
通过 `runtime.require(KcpRuntime.server("game"))` 获取服务。
使用非内联的受管执行器；`ZeroRuntimeExecutors.direct()` 会被拒绝。

配置前缀为 `zero.kcp.<listener>.`，如 `profile`、`maxSessions`、`maxFrameBytes`、`heartbeatIntervalMillis`；
沿用 runtime 的 programmatic/system/environment/file/remote 来源。分项默认 auto，跟随选中的场景。
多个监听器使用不同名字，端口、配置、预算和指标相互隔离。配置启动时生效，不热切换已建立的可靠会话。

可选借用 `NetworkRuntime.IO_RESOURCES`；同时安装 `MonitorRuntimeComponent.module()` 后，
KCP 每秒在受管后台采样低基数指标。服务最多一个在途采样，停止时取消；慢观察器不会堆积任务或阻塞 IO。
Grafana、Prometheus 告警模板位于 `examples/kcp-tcp-login/observability/`。

可选依赖修正：`zero-runtime-kcp` 仅以 optional 方式声明 `zero-runtime-monitor`，并隔离监控类型的类加载。无日志/监控依赖的应用无需迁移即可启动；需要上述指标采样的应用应在 POM 中显式依赖 `zero-runtime-monitor` 并安装其 provider。自定义快照消费者仍可直接使用 `KcpServer.watch(...)`。

## 6. 预算和调度语义

- 从固定间隔全表更新改为下一截止调度，每会话一个复用记录、每服务一个等待计时器、单轮最多 64 个到期会话。
- 空闲会话无需更新 KCP 算法；活跃发送主动 flush，socket 根据场景在同一 EventLoop 批次合并刷新。
- ZeroBinary 提交按可靠编码尺寸预算，未知 codec 仍保守预留且只在 IO 线程编码。
- 已确认分片共享整帧存储时不提前归还字节；整帧最后引用释放才归还自身预算，无需等待后续整个发送队列清空。
- `maxInboundBytesTotal` 只限制业务队列与在途 handler，不代表算法接收窗口、socket 和全部 JVM 内存上限。
  停止传输不会虚假释放仍由异步 handler 持有的数据预算。

## 7. 验证与使用入口

- [独立 Java 示例](../../examples/kcp-tcp-login/README.zh-CN.md)：五场景真实 TLS 登录、UDP 可靠回显、新票据恢复、TCP 回退、清理。
- [性能与弱网记录](../reports/kcp-scenario-performance-20260928.zh-CN.md)：同机基线、隔离复测、分档在途负载与测量限制。
- [完整验证证据](../../tasks/archive/20260928-kcp-scenario-optimization/VERIFY.md)：本轮质量、测试数、覆盖率、依赖和架构检查。

本轮场景配置文档不重复定义高级传输协议；FEC、UDP 保护、无缝 NAT 漂移和跨节点会话见[高级传输迁移](20260928-kcp-advanced-transport.md)及[协议契约](../reference/kcp-transport-contract.zh-CN.md)。
未验证公网容量、长稳、Linux epoll、真实手机耗电和 C#/TypeScript SDK；不能将本机回环结果当作生产 SLO。
