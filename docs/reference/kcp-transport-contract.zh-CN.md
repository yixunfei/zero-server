# KCP 接入设计与协议契约

日期：2026-09-28；服务端闭环与多场景优化均已确认；独立 `zero-net-kcp` Adapter 和可选 `zero-runtime-kcp` 装配。

## 背景与依赖

过去的 KCP 仅有 fail-fast 占位。本次增加真实可靠传输、TCP 控制通道授权、资源限制、可选 FEC、可选 UDP 保护、路径迁移和会话所有权迁移。
保持 `IServer`、`IConnection`、`ServerFrameHandler`、`ProtocolFrameCodec` 和 ZeroBinary 业务帧格式。
不扩展账号系统、业务协议 ID、Actor 调度或其他传输。

依赖固定为 `com.github.l42111996:kcp-base:1.6.2`，来源为
[java-Kcp](https://github.com/l42111996/java-Kcp)，其 Maven POM 和上游 LICENSE 声明 Apache-2.0。
仅调用 `kcp.Kcp` 算法，不实例化上游 KcpServer/KcpClient/Ukcp、线程池或定时器。
`kcp-fec` 仅作为可选 FEC 算法依赖；FEC 是否启用由 `KcpTransportOptions` 下发，默认配置不强制开启。
排除上游 Netty、SLF4J 和 JCTools 传递依赖；Netty 统一由框架的 `4.2.17.Final` 提供。
不复制上游实现源码；制品分发应保留第三方许可证声明。

## 组合根与状态机

```text
TCP TLS -> 业务身份服务认证 -> ESTABLISHED + SecurityContext
        -> issueTicket(control) -> 安全下发 conv / key / expiresAt
        -> 首个认证 UDP 包（来源 IP 与 TCP 相同）
        -> ZKCP v1 保护/FEC/路径验证 -> KCP 有序消息 -> ProtocolFrameCodec -> 业务执行器
        -> 空闲 / 授权过期 / TCP 关闭或重新认证 / 显式回退 / 服务停止
        -> 票据撤销 + 算法释放 + 业务 onClose
```

一个控制连接最多拥有一份有效票据；重新签发立即撤销旧票据。
会话号是非零 32 位值，不是身份凭证。密钥为 32 个 SecureRandom 字节，只能由可信控制通道下发。
默认 `requireControlTls=true`，并检查框架设置的 TLS_ESTABLISHED，ESTABLISHED、SUBJECT_ID、SECURITY_CONTEXT。
票据有效期取 KCP 配置与登录身份有效期的较早者；重新认证产生不同 SecurityContext 时旧票据失效。
控制连接属性是框架内部信任边界，业务代码不能自行伪造已认证属性。

初次 UDP 包需与 TCP 源 IP 相同；启用路径迁移后，候选地址必须完成有界 challenge/response，验证前不改变路由。
迁移成功后旧路径只在排空期限内接收，不能用于重新切回；重绑仍受票据、代际和候选路径限制。未知票据、验签失败、重放、错误地址和超长包不回复，不为其创建算法会话。
一份有效票据在收到首包前也占用 maxSessions 和生命周期预算，并受空闲/绝对超时回收。

## ZKCP v1 线格式

外层字段为大端；内部 KCP segment 使用上游标准小端。每个 UDP 数据报不超过连接描述中的 `maxDatagramBytes`；策略标签和 FEC 元数据均计入该上限。

| 偏移 | 长度 | 内容 |
| --- | --- | --- |
| 0 | 4 | ASCII `ZKCP`，0x5a4b4350 |
| 4 | 1 | version=1 |
| 5 | 1 | 发送方向：客户端 0，服务器 1 |
| 6 | 1 | type：DATA、FEC、PROBE、CHALLENGE、RESPONSE、QUIESCE、QUIESCED |
| 7 | 1 | headerLength=32 |
| 8 | 2 | protection wire ID；0=NONE、1=HMAC-SHA256、2=ChaCha20-Poly1305、3=AES-GCM，亦可注册自定义策略 |
| 10 | 2 | 保留字段，必须为 0 |
| 12 | 4 | conv；客户端按原始 32 位位模式处理 |
| 16 | 8 | 会话所有权 generation |
| 24 | 8 | 正的方向序号，每个票据/方向从 1 单调递增 |
| 32 | 0..maxDatagramBytes | 保护后的 body；AEAD 将固定头作为 AAD，标签长度由策略决定 |

不允许反射另一方向的包；每个方向独立保留 64 包去重窗口。窗口内乱序只接受一次，太旧的包丢弃后由 KCP 重传恢复。
重传须重新封装为新的外层序号；序号耗尽时更换票据，不回绕。保护策略必须拒绝错向、错误代际、标签错误和重放。
空 body 的客户端包获得一个认证空 body 回复，客户端不再响应此回复，防止心跳循环。
客户端应以小于 idleTimeout 的间隔发心跳（建议三分之一）；心跳不能延长授权绝对有效期。
NONE 仅适合受控测试；HMAC 提供认证与完整性但不加密业务内容；ChaCha20-Poly1305 和 AES-GCM 提供机密性与完整性。部署可通过策略校验拒绝 NONE。

客户端可使用 `KcpDatagramCodec(ticket, false)` 或按上述布局实现封装；标准裸 KCP 客户端须添加这一层。
FEC 由策略选择 NONE、XOR 或 Reed-Solomon；组缓存、恢复 CPU 和总字节都有上限，恢复后的数据仍进入 KCP 顺序与重放检查。不启用 CRC、stream 或 ack-mask。默认 BALANCED：nodelay=true、fast-resend=2、最小 RTO=100ms、拥塞窗口开启，MTU=1200、interval=20ms、window=128；其他预设见[场景指南](../guides/kcp-scenarios.zh-CN.md)。
每个 KCP message 恰好对应一个完整 ZeroBinary frame，**没有 TCP 四字节长度前缀**。
最大消息为 `min(ServerOptions.maxFrameLength, KcpLimits.maxFrameBytes)`，BALANCED 默认 32768 字节（含帧头）。配置须满足 `maxFrameBytes <= min(receiveWindow,255) * (mtu-24)`。
接收前验证所有 segment 的 conv、command、长度与分片计数；坏包隔离到本会话。

## ZKCI v1 控制面描述

KcpConnectInfo.encode/decode 是可信 TLS 控制面的应用 payload，不改变 ZKCP v1 UDP 格式。全部大端，至多2048字节，无尾随数据；布尔只接受0/1。格式包含密钥，本身不提供身份认证。

| 顺序 | 字段与长度 |
| --- | --- |
| 1 | magic=0x5a4b4349（4）、version=1（4）、ASCII host 长度 L（4）、host（L，1..253）、UDP port（4） |
| 2 | conv（4）、key（32）、expiresAt UTC epoch millis（8） |
| 3 | 稳定 profile ID（4）：LOW_LATENCY=0、BALANCED=1、MOBILE=2、LOW_FREQUENCY=3、BULK=4 |
| 4 | 各4字节：mtu、intervalMillis、sendWindow、receiveWindow、noDelay、fastResend、minimumRtoMillis、congestionControl、flushBatch |
| 5 | 各4字节：maxSessions、maxQueuedFrames、maxPendingSegments、maxFrameBytes；maxInboundBytesTotal（8） |
| 6 | 各8字节毫秒：bindTimeout、heartbeatInterval、idleTimeout、progressTimeout、ticketLifetime；requireControlTls（4） |
| 7 | protectionId、allowUnauthenticated、maxDatagramBytes、maxFecBytesTotal；FEC wireId/dataShards/parityShards/flushDelay/expiry/maxGroups/maxBytes；路径 enabled/timeout/retryInterval/retiredPathLifetime/maxCandidates |

总长度268+L。独立 Python struct 固定向量在 zero-net-kcp/src/test/resources/zkci-v1.hex，Java测试同时校验编解码。场景ID不依赖 enum.ordinal；布局变化需新版本。

KcpRecovery 经项目 KcpControlPlane 获取和撤销描述：转换期间拒绝新业务发送，关闭后迟到授权撤销，不自动重放。地址变化可使用 `KcpClient.rebind()` 完成路径挑战；跨节点迁移使用更高 generation 和所有权 CAS，回退屏障不代表业务事务完成。

## 资源与并发

- 一个 UDP socket 的所有 KCP 状态由一个 Netty EventLoop 串行访问；workerThreads 不表示单 socket 可并行处理多个会话。可通过多个显式服务实例分片。
- `NettyIoResources.bindDatagram` 是 Adapter 的共享资源入口；纯 UDP/KCP 不创建 TCP boss。
- 服务按下一截止时间调度，最多一个等待计时器、每会话一个复用记录。更改截止时间 O(log n)，每轮至多处理 64 个到期会话；空闲算法不更新，授权最迟每 250ms 复查。客户端同样复用最早计时器。
- maxSessions 包括待绑定票据；收包最多保留约两个 KCP 接收窗口的 segment，单数据报大小受 MTU 限制。
- 单连接 segment、NetworkTuning 的连接/服务字节预算同时生效；ZeroBinary 在 IO 提交前使用线程安全 encodedLength 计算上界，自定义 codec 仍按最大帧预留且只在 IO 编码。KcpRetainedFrame 在该帧最后一个 retainedSlice 释放时归还预算；部分 ACK 不提前释放，但不再等待后续所有帧排空。
- 入站队列每连接限制 maxQueuedFrames，含一个正在执行的 handler；默认服务总业务预算 64MiB，包含业务帧、固定开销和生命周期回调。关闭不能提前释放仍由 handler 持有的预算。
- 业务回调由调用方的非内联执行器执行，同一连接 open -> frames -> close 串行，异步返回也保持顺序。下一帧执行前重查授权；跨连接可以并发。
- onException 必须非阻塞，可从 IO 或回调线程触发；异常绑定 NetErrorCode，观察器二次失败计数并输出 OBSERVER_FAILED。计数器不含用户或 conv 标签。
- 启动失败回收 socket；借用 IO 组不被关闭。服务实例停止后不可重启，应创建新实例。业务执行器由组合根关闭，必须晚于服务停止及在途 handler 完成。
- close/stop 不取消已执行的业务事务；永不完成的 handler 会持续占据有限预算，需要应用的异步超时策略。

`KcpConnection.send` 成功表示已进入本地有界 KCP 队列，**不是远端 ACK 或业务提交确认**。
首次绑定使用 bindTimeout，空闲使用 idleTimeout，发送无进展使用 progressTimeout；有心跳但无 ACK 进展仍会超时。KcpClient 自动维护 hello、心跳与重传。每帧主动 flush 算法，socket 刷新按预设合并。

## 显式 TCP 回退

客户端先暂停新的 KCP 业务发送，经原 TCP 控制连接发出应用定义的回退请求。
服务端调用 `fallbackToTcp(control, conv)`，校验控制连接拥有权，等待本地撤销完成后向客户端确认。
此后新请求走 TCP；旧票据包被丢弃。回退不自动重发未确认请求，也不等待/取消已经进入业务执行的事务。
跨传输顺序和重复处理由应用的 requestId、Actor 序列与幂等结果表定义；不能将回退视为 exactly-once。
账号、票据和回退请求的业务协议 ID 由项目自己分配；框架不硬编码登录协议。

## 装配示意

```java
var kcp = new KcpServer(ServerOptions.kcp("0.0.0.0", 9001), KcpOptions.defaults(),
        new ZeroBinaryFrameCodec(), handler, listener, runtimeExecutors.logicExecutor(), sharedIo);
kcp.start();
// 在生产 TCP onOpen 或受权的业务请求中：
kcp.issueTicket(tcpConnection).thenCompose(ticket ->
        tcpConnection.sendFrame(applicationTicketFrame(ticket)));
// 在同一控制连接的回退请求中：
kcp.fallbackToTcp(tcpConnection, conv).thenCompose(ignored ->
        tcpConnection.sendFrame(applicationFallbackAck()));
```

`sharedIo` 可由 `NetworkRuntime.ioModule` 注入；自定义 RuntimeComponentProvider 声明依赖 IO_RESOURCES 和 EXECUTORS，
返回 `ComponentContribution.lifecycle(kcp)`，由 runtime 保证服务先于 IO 关闭。不强制修改 starter 默认模块。
处理返回 stage 的失败并进入项目统一错误响应，不把密钥放进 attributes、日志或指标。

## 验证与边界

可执行例子见 [Java TLS/KCP 联调测试](../../zero-net-kcp/src/test/java/group/zn/zero/net/kcp/KcpTcpLoginExampleTest.java)，
可运行步骤见 [联调指南](../../examples/kcp-tcp-login/README.zh-CN.md)。
测试覆盖固定字节布局、独立 HMAC/AEAD 向量、真实 UDP 双向丢包/重传/乱序/重复、FEC 分片恢复、鉴权、路径挑战、回退、
坏包、超时、入出站预算、异步关闭和借用 IO 回收。最新执行证据见 [高级传输迁移说明](../migrations/20260928-kcp-advanced-transport.md)。

尚无公网容量/长稳、DDoS、Linux epoll 或 C#/C/TypeScript 客户端互通结论；Redis/跨节点恢复、业务去重存储和自动 TCP 重放仍由应用边界负责，框架不自动重放业务。

## 多场景接入与新验证

受管 Java 客户端、五场景独立 main 示例、固定种子弱网矩阵、FEC/保护/路径/恢复竞态及引用计数测试见[场景指南](../guides/kcp-scenarios.zh-CN.md)与[高级传输迁移](../migrations/20260928-kcp-advanced-transport.md)。
