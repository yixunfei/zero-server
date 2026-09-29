# KCP 多场景接入指南

2026-09-28；Java 21。通过可选 `zero-runtime-kcp` 使用受管客户端、配置与生命周期；
底层 Adapter 仍可独立使用。所有预设保持可靠有序消息语义。

## 选择场景

以下帧上限包含 ZeroBinary 头部，不只是 payload。MTU 指 KCP 本体，UDP payload 还包含 50 字节认证头尾。

| 预设 | 典型业务 | MTU / interval | 窗口 | 最大完整帧 | 心跳 / 空闲 / 无进展 | 刷新批次 |
| --- | --- | --- | --- | --- | --- | --- |
| LOW_LATENCY | 动作输入、帧同步、小房间高频指令 | 1200 / 10ms | 64 | 8KiB | 10 / 30 / 30秒 | 1，立即 |
| BALANCED | RPG、房间状态、AOI 增量 | 1200 / 20ms | 128 | 32KiB | 10 / 30 / 30秒 | 16 |
| MOBILE | 移动弱网、重连恢复 | 1100 / 20ms | 128 | 32KiB | 15 / 60 / 45秒 | 8 |
| LOW_FREQUENCY | 大厅、低频可靠通知 | 1200 / 50ms | 64 | 16KiB | 30 / 120 / 60秒 | 16 |
| BULK | 有限批量、小型可靠数据块 | 1200 / 40ms | 256 | 128KiB | 10 / 30 / 30秒 | 32 |

默认 BALANCED；所有预设默认启用拥塞控制、TLS 控制通道、10秒建链期限、30分钟票据上限。
LOW_LATENCY 的最小 RTO 为 30ms，其余为 100ms；LOW_FREQUENCY/BULK 使用常规模式，其他使用 noDelay。
全部 fastResend=2。批次大于 1 时同一 EventLoop 轮次合并 flush，达到包数上限立即刷新。
这些值是可解释的起点，不能代替具体游戏的帧率、带宽和时延测试。

- 帧同步输入：每帧消息控制在小包内，使用业务 tick/requestId 去重与追帧；不要把大快照混入同一高频连接。
- RPG/AOI：先在 scene actor 聚合一个周期内的状态变更，再将结果交给 KCP；已进入可靠队列的消息不被覆盖。
- 移动网络：连接失败后由 `KcpRecovery.reconnect()` 重新领票和握手，服务端使用业务快照/序号恢复状态。
- 低频大厅：让自动心跳维持连接，不需要业务自建轮询线程；低频业务也可以直接选择 TLS TCP。
- 批量数据：只使用有界分块，资源下载与超过帧上限的数据交给 TCP；可靠队列有序，不能绕开大消息的阻塞影响。
- 交易/账号/私密内容：优先使用 TLS TCP，并实现请求幂等；若业务选择 KCP，必须选择 ChaCha20-Poly1305 或 AES-GCM，HMAC/NONE 只提供认证或完整性，不提供机密性。

## 一次安装

应用显式依赖 `zero-runtime-kcp`（由 zero-bom 管理版本）。下面是已由测试覆盖的 local runtime 方式：

~~~java
var config = new MapZeroConfig(Map.of(
    "zero.kcp.game.profile", "MOBILE",
    "zero.kcp.game.maxSessions", "1024"));
var composition = RuntimeComposition.builder(RuntimeProfile.local())
    .install(RuntimeBasics.module(config,
        () -> ZeroRuntimeExecutors.localPrototype("game", 2)))
    .install(KcpRuntime.module("game", "0.0.0.0", 9001, KcpProfile.BALANCED, handler));
try (var runtime = composition.build()) {
    runtime.start();
    var kcp = runtime.require(KcpRuntime.server("game"));
    // 在应用受管生命周期中运行；不能在这里立即退出。
}
~~~

runtime 负责服务、socket、执行器的停止次序。业务 handler 使用逻辑执行域，内部游戏状态仍应通过对应 actor 修改。
模块创建/diagnose 不创建 IO。可安装 game、lobby 等不同名字的监听器，实现端口、场景、预算和指标隔离。

已有 `ProductionAssembly` 也支持直接 `install(KcpRuntime.module(...))`，无需另写 factory：

~~~java
var assembly = ProductionAssembly.builder(profile, config, managedExecutors)
    .install(MonitorRuntimeComponent.module())
    .install(KcpRuntime.module("game", "0.0.0.0", 9001, KcpProfile.BALANCED, handler));
~~~

`managedExecutors` 由项目组合根提供；上例需在应用 POM 中显式声明 `zero-runtime-monitor`，并通过 `install` 启用每秒自动监控采样。
最小 KCP 消费者不需要日志或监控依赖；只把监控 jar 放入类路径不会创建采样任务。自定义观测可直接调用 `KcpServer.watch(interval, managedExecutor, snapshotConsumer)`，不要求使用框架监控实现。
生产组合器入口、真实 UDP 接收计数与自动采样已有集成测试。

单 UDP socket 始终只使用一个 EventLoop；增加 workerThreads 不会自动把它并行化。
可选借用 `NetworkRuntime.IO_RESOURCES`；没有配置时服务自己持有 IO，停止时归还。

底层定制入口为 `KcpOptions.builder(profile).tuning(...).limits(...).timeouts(...).build()`。
分组记录 `KcpTuning/KcpLimits/KcpTimeouts` 明确类型和约束，不需要记忆十几个位置参数。
默认业务执行器不能使用 Runnable::run 或 CallerRuns；runtime 入口会拒绝内联资源装配。

## 配置来源和覆盖

键以 `zero.kcp.<listener>.` 开头，支持已有 runtime 的 programmatic、system property、environment、file 和 remote 来源。
显式设置 profile 后，未覆盖的参数跟随该预设。分项默认 `auto`，不是另一套硬编码默认值。
配置仅启动时生效；不热切换现有可靠会话的参数。

| 类别 | 配置后缀 |
| --- | --- |
| 监听 | profile、host、port |
| 算法 | mtu、intervalMillis、sendWindow、receiveWindow、noDelay、fastResend、minimumRtoMillis、congestionControl、flushBatch |
| 容量 | maxSessions、maxQueuedFrames、maxPendingSegments、maxFrameBytes、maxInboundBytesTotal |
| 出站预算 | maxPendingBytesPerConnection、maxPendingBytesTotal |
| 时限 | bindTimeoutMillis、heartbeatIntervalMillis、idleTimeoutMillis、progressTimeoutMillis、ticketLifetimeMillis |
| 安全 | requireControlTls |

环境变量使用整个别名转大写并将点改为下划线，例如 `ZERO_KCP_GAME_MAXSESSIONS`。
profile 名称使用表中的大写枚举；布尔值仅接受 true/false，非法值不会悄悄落回默认值。
预设切换先于分项覆盖；不一致的帧长/窗口/时间组合在 socket 创建前拒绝。

默认 maxSessions=1024（包含待绑定票据）、maxPendingSegments=512、maxQueuedFrames=32；LOW_LATENCY 的业务帧队列为 8。
每服务默认业务入站预算 64MiB，NetworkTuning 默认出站总预算 256MiB，同时受每连接段数预算限制。
这些字节预算不等于整个 JVM 的堆上限：算法接收窗口、socket、认证状态和对象本身另占内存。
接收段的粗略上界与 `maxSessions × 2 × receiveWindow × (mtu + segment开销)` 成正比。
多个监听器预算要相加；容器小内存部署应先降低 maxSessions 和窗口，再做目标负载测试。

## TLS 登录和两端参数

控制连接完成真实 TLS、身份认证和框架 ESTABLISHED 后：

~~~java
kcp.issueConnectInfo(control, "udp.example.org", 9001)
    .thenCompose(info -> control.sendFrame(applicationTicketFrame(info.encode())));
~~~

业务自行分配票据/续领/回退的协议 ID。返回的 `KcpConnectInfo` 包含权威端点、票据、场景及完整配置；
公开域名/端口由部署组合根指定，不能直接把 0.0.0.0 或 NAT 内网端口发给客户端。
encode 的字节含密钥，只经原 TLS 控制通道发送；toString 不打印密钥。

客户端收到可信字节后：

~~~java
var info = KcpConnectInfo.decode(ticketPayload);
var client = new KcpClient(info, runtimeExecutors.logicExecutor(), clientHandler, listener, sharedIo);
client.connect().thenCompose(connected -> connected.sendFrame(request));
// 应用退出时等待 client.close()，随后再关闭其执行器。
~~~

connect 确认收到服务端认证响应；onOpen、业务帧、onClose 由统一有界队列串行调度。
客户端自动发送 hello/心跳、重传和检查授权/空闲/无进展超时，无需自行 pump。
客户端使用自有 IO 时按实例创建资源；多客户端压测/工具应显式复用 IO，避免每连接独占线程。
描述的 DNS 解析发生在创建客户端时，不能在业务 actor 或 Netty IO 中进行不可控 DNS 操作。

## 恢复与回退

`KcpControlPlane` 只要求 acquire（经 TLS 获取新描述）和 revoke（等待服务器撤销确认）。
实现负责超时、业务协议 ID、错误响应和原连接身份；真实示例使用有限超时 SSLSocket，并把阻塞读放在框架 remote IO 执行域。

`KcpRecovery` 提供 NEW → CONNECTING → KCP、FALLING_BACK → TCP、FAILED、CLOSED 状态：

1. reconnect 关闭旧本地连接，撤销旧票据，获取新票据并握手；网络换址使用这个流程。
2. 转换期间 send 立即失败，不暗中缓存业务。
3. fallbackToTcp 关闭本地 KCP，再等服务端撤销；完成后应用才能提交新的 TCP 请求。
4. 未确认旧请求不会重发。跨传输的去重、事务完成与逻辑顺序由应用 requestId/actor 负责。
5. close 可使在途恢复失效；迟到票据会被撤销，不创建新 socket。

移动预设与路径策略相互独立。启用 `KcpPathOptions.validated()` 后客户端可通过 `KcpClient.rebind()` 完成有界 challenge/response 和旧路径排空；未启用时地址仍固定，必须重新领票。身份过期还需要先重新登录 TCP。

## 运行与观测

[独立例子](../../examples/kcp-tcp-login/README.zh-CN.md)一条运行命令可跑完五场景的 TLS、KCP、重连和回退。
若 runtime 已安装 MonitorRuntimeComponent，KCP 模块每秒在后台执行域采样，最多一个采样在途，停止时取消。
也可显式使用 `server.watch(interval, executor, new KcpTelemetry(name, registry))`；每服务只允许一个订阅。

指标覆盖授权/连接数、入出站包、拒绝与关闭原因、算法更新/flush 次数、入出站预算。
不把上游全局 Snmp 冒充单连接 RTT/重传指标；这些 per-connection 指标当前未提供。
Grafana/Prometheus 示例位于 `examples/kcp-tcp-login/observability/`，需要接入项目现有 exporter。

排查顺序：TLS/身份有效期 → 公网 UDP 端口和防火墙 → 地址与票据代际 → 描述参数 → 拒绝原因 → 预算/handler积压 → 实际链路丢包。
大量 close.HEARTBEAT_TIMEOUT 要区分无有效入站和无 ACK 进展；收到心跳不能保证发送队列可推进。
停止完成只释放传输；尚未完成的 handler 保留自身预算，应用应给异步业务设置期限。

验证范围和本机测量见 [迁移/证据](../migrations/20260928-kcp-scenario-optimization.md)。
未验证公网容量、长期运行、Linux epoll、真实手机耗电或 C#/TypeScript SDK；高级传输能力的实现和限制见[协议契约](../reference/kcp-transport-contract.zh-CN.md)。
