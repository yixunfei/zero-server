# zeroServer 全量代码审计报告（Round 4，2026-10-06）

状态：审计完成。本轮为独立全量复查：先清空历史报告，再重新精读与扫描全部 61 个 Maven 模块（约 1,530 个 src/main Java 文件）。

## 结论概览

- **0 Critical / 0 High**：核心安全防线（epoch fencing、KCP 票据、GM fail-closed 授权链、TLS 门控、常数时间比较）全部在位且正确。
- **4 Medium**：受信代理解析器同步 DNS 阻塞向量（M1）；zero-net 网络层三处（M2–M4：鉴权期入站无字节预算、TLS 握手失败不可观测、TLS+unmanaged 提前 onOpen）。
- **9 Low**：安全工具类 4 项（L1–L4）+ zero-net 网络层 5 项（L5–L9）。
- **11 性能/结构观察项**（P1–P11）：全部有界，不构成缺陷。
- 构建验证：`mvnw -o compile` 与 `mvnw -o verify`（Checkstyle + PMD + SpotBugs 全开）均 exit 0，无任何告警。

## 审计方法与覆盖度

- **就地精读**（全文逐行）：Actor 调度核心（`ExecutorActorScheduler`/`OrderedActorHandlers`/`LocalActorScheduler`）、世界服务（`LocalWorldService`/`WorldDispatchRegistry`）、runtime 装配链（`RuntimeAssembler`→`RuntimePlanner`→`RuntimeBuildTransaction`→`DefaultGameRuntime` 及 `LifecycleCallBoundary`/`StartedComponentLedger`/`BuildResourceLedger`）、production 门面（`ZeroProductionRuntime`/`ProductionAssembly`）、bootstrap 执行域（`ZeroRuntimeExecutors`/`ExecutorShutdown`）、starter 调度器（`LocalManagedScheduler` 全文 1422 行）、持久化核心（`DefaultPersistenceManager` 全文）、双层缓存（`LayeredCacheService`/`CacheLoadCoordinator` 全文）、Netty 帧处理（`NettyFrameChannelHandler`/`NettyProtocolFrameDecoder`/帧长度防护）、KCP 会话（`KcpSessions`）、GM 授权（`GmOperationAuthorizer`/`GmSourceIpPolicy`）、Kafka RPC（`KafkaRpcTimeoutWheel`/`KafkaRpcPendingRequests` 全文）、热更配置（`LocalConfigFileWatcher` 全文）、协议编解码（`ZeroBinaryFrameCodec` 全文）。
- **子代理全文件审计**：zero-security（18/18 文件）、zero-net（49/49 src/main 文件，发现经主审计逐条证据核验后并入）。
- **全仓模式扫描**：裸线程池/`new Thread`/`Thread.sleep`/`printStackTrace`/空 catch/ECB/DES/MD5/`ObjectInputStream`/`new Random`/静态可变集合——src/main 全部干净（命中仅在 scripts/测试源集，属预期）。
- **抽查级**（结构+定点 grep，未逐行）：zero-net-kcp 剩余、zero-data 适配器剩余、zero-gm 剩余、zero-codegen、zero-monitor、zero-log、discovery、data-mongo/redis/postgresql 适配器。这些模块无 Critical 信号（静态门禁全绿 + 模式扫描无命中）。

## 发现：中危（4）

### M1 受信代理解析器对不可信 IPv6 字面量触发同步 DNS 解析

- 证据：`zero-security/src/main/java/group/zn/zero/security/TrustedProxyResolver.java:30-43`。`requireLiteral` 的正则 `[0-9a-fA-F:.]+` 对含 `:` 分支不做任何 IPv6 结构校验（组数、每组 hex 位数），随后直接调用 `InetAddress.getByName(source)`。Java 对无法按字面量解析的含冒号字符串会退化为**主机名 DNS 查询**。
- 触发路径：`resolve(peerAddress, forwardedFor)` 中 XFF 值攻击者可控；当直连 peer 落在受信代理集、转发链全受信或 CIDR 配置过宽时，`requireLiteral` 逐跳解析攻击者构造的非法 IPv6（如 9 组冒号段），请求线程同步阻塞数秒，可放大为放大型 DoS。
- 建议：对含 `:` 分支做严格结构校验（组数 ≤8、每组 ≤4 hex、可选嵌入 IPv4 合法性），校验通过后不再调用 `getByName`；或改用 `InetAddress.getByName` 的替代——纯字面量解析（自实现或 `InetAddresses` 类库语义），彻底移除 DNS 语义。

### M2 鉴权期入站队列只有帧数预算，无字节预算

- 证据：`zero-net/src/main/java/group/zn/zero/net/netty/NettyProductionLifecycleSession.java:457-466`——`enqueueDuringAdmission` 仅检查 `pendingFrames.size() + inboundInFlight >= maxInboundFrames`（默认 1024），无字节维度；单帧上限来自 `NettyTcpServer.java:250-255` 的 `LengthFieldBasedFrameDecoder(maxFrameLength)`（默认 16MB）。
- 影响：未认证连接可在 `authenticationTimeout`（默认 10s，`ProductionNetworkConfig.java:43`）窗口内排队至多 1024 帧 × 16MB，单连接内存放大仅受攻击者带宽约束；对比之下顺序模式有 `maxOrderedInboundBytes` 字节预算（`NetworkTuning.java:14`）而 admission 队列没有。
- 建议：admission 队列增加字节预算（复用 `retainedBytes` 语义），超限按 `INBOUND_OVERFLOW` 拒绝。

### M3 TLS 握手失败对 observer 完全不可见

- 证据：`zero-net/src/main/java/group/zn/zero/net/netty/NettyFrameChannelHandler.java:160-162`——握手失败分支仅 `context.close()`；`NetErrorCode.java:104` 定义的 `TLS_HANDSHAKE_FAILED` 在全模块无 emit 点。
- 影响：生产 TLS 下坏证书/中断握手只产生 ACCEPTED→CLOSED 遥测，与静默断开不可区分，运维无法定位 TLS 层故障。
- 建议：失败分支先 emit `CHANNEL_ERROR(TLS_HANDSHAKE_FAILED)` 再 close。

### M4 TLS+unmanaged 组合在握手完成前触发 onOpen

- 证据：`zero-net/src/main/java/group/zn/zero/net/netty/NettyFrameChannelHandler.java:153-166`——`sslHandler != null` 且 `productionLifecycle == null` 时 `channelActive` 立即 `openListener()`，早于 `handshakeFuture` 回调写入 `TLS_ESTABLISHED`（line 156）。
- 影响：业务 `onOpen` 时无法感知加密状态，早期写出被 SslHandler 缓冲延迟；语义与 production 模式（等握手成功才 start）不一致。
- 建议：该组合等 `handshakeFuture` 成功后再 `openListener()`。

## 发现：低危（4）

### L1 敏感属性与 TLS 材料引用黑名单可别名绕过

- 证据：`zero-security/src/main/java/group/zn/zero/security/SecurityValues.java:33-38` 仅按 `endsWith("token"/"password"/"secret"/"key")` 过滤；`TlsMaterialProvider.java:17` 的 materialReference 正则黑名单同类可绕过。
- 影响：`passwd`、`credentials`、`authorization`、`bearer`、`passw0rd` 等别名的属性值可进入 `SecurityContext.attributes` 随传播链扩散（`SecurityContext.toString` 已 REDACTED，泄漏面限于内存与下游消费方）。
- 建议：改为 allow-list，或至少扩展为 contains 语义并覆盖常见别名。

### L2 SecurityChain.failClosed 未闭合 TLS 维度

- 证据：`zero-security/src/main/java/group/zn/zero/security/SecurityChain.java:38`——`failClosed()` 构造 `tlsRequired=false` 且 `materials=null`：认证与重放 fail-closed，但传输加密不强制。
- 影响：命名暗示全闭合；生产误用导致明文传输。
- 建议：javadoc 明示"仅认证维度闭合"，或提供带 fail-closed TLS 材料的变体。

### L3 ReplayProtection.acceptForTests 与生产实现同签名空间

- 证据：`zero-security/src/main/java/group/zn/zero/security/ReplayProtection.java:16-18`——静态工厂对生产代码同样可调用且类型无差异，误接线即重放保护全开，无任何运行时标记。
- 建议：测试实现移入测试源集或包私有化。

### L4 GmSourceIpPolicy.allows 热路径调用 InetAddress.getByName

- 证据：`zero-gm/src/main/java/group/zn/zero/gm/GmSourceIpPolicy.java:34`——与 M1 同模式；但输入是 `operatorIp`（来自连接层 socket 地址，非自由文本），前置 `requireLiteral` 已过滤，且 GM 为低频路径、失败 fail-closed 返回 false。
- 影响：可控性弱、频率低，属 M1 的低风险关联项；正常 IP 字面量不触发 DNS。
- 建议：与 M1 一并改为纯字面量解析，消除同类模式。

### L5 HTTP 入站无服务器级总字节预算

- 证据：`zero-net/src/main/java/group/zn/zero/net/netty/NettyHttpServer.java:206-211`（guard 以 maxFrameLength 作 pending 字节上限）+ `NettyHttpRequestGuard.java:61-67`。
- 影响：每连接聚合中 + 执行中 + 待队列最高约 3×maxFrameLength（默认约 48MB），跨连接无共享上限（TCP 出站有全局 256MiB `OutboundBudget` 而 HTTP 入端没有），仅 30s 请求期限兜底。
- 建议：增加 server 级 HTTP 入站字节预算。

### L6 reject() 回调链抛异常会跳过 context.close()

- 证据：`zero-net/src/main/java/group/zn/zero/net/netty/NettyProductionLifecycleSession.java:733-746`——emit 与 `observerFailureCallback`（line 744）位于 `context.close()`（line 746）之前；EventLoop 停用窗口中 `notifyListenerException`（`NettyFrameChannelHandler.java:491`）的 `execute` 可抛 `RejectedExecutionException`。
- 影响：连接停留 REJECTED 状态不关闭（仅停机窗口内可达）。
- 建议：`close()` 移入 finally。

### L7 UDP 解码失败回调使用未注册的临时孤儿连接

- 证据：`zero-net/src/main/java/group/zn/zero/net/netty/NettyUdpServer.java:274-280`——`new NettyUdpConnection(...)` 不入 peers 表即传给 `safeException`。
- 影响：`onException` 收到永无 `onClose` 的孤儿对象，监听方按 connectionId 的状态关联失效。
- 建议：先查 peers 既有会话，未注册时仅计数不回调（或直接丢弃）。

### L8 HTTP 格式类错误误用 401

- 证据：`zero-net/src/main/java/group/zn/zero/net/netty/NettyHttpServer.java:309-311`（重复头）、`339-340`（metadata 解码失败）均 `writeUnauthorized`。
- 影响：malformed request 与未授权混淆，影响客户端语义与监控聚合。
- 建议：格式错误改 400。

### L9 HTTP 故障观察点丢弃全部诊断信息

- 证据：`zero-net/src/main/java/group/zn/zero/net/netty/NettyHttpServer.java:263-266`——仅 `log(ERROR, code)`，无 cause 类名/堆栈/上下文。
- 影响：500 根因不可排查（安全考虑可不落业务文本，但至少应有异常类名）。
- 建议：附异常类名 + 脱敏上下文。

## 发现：性能/结构观察项（11，均有界、非缺陷）

- **P1** `zero-data/.../DefaultPersistenceManager.java:440-443`：每次 flush 对全部脏入口做 `filter+sorted(generation)+limit` 全量排序，10 万上限脏键时每周期 O(n log n)。停机外高频周期下有浪费；可改用按 generation 的最小堆或环形分代。当前上限 100k，量级可接受。
- **P2** `zero-cache/.../LayeredCacheService.java:165,509-514`：`l2FailureCooldown=0` 默认兼容模式为 fail-open（L2 故障当 miss 穿透到 loader）。注释已明示生产应显式配置正冷却；建议组合根默认给正值。
- **P3** `zero-actor/.../ExecutorActorScheduler.java:432-447`：执行超时隔离（`queue.isolated`）在 handler 真实挂死时该 lane 永久拒绝且滞留 1 个全局许可，需重启进程回收。这是有意的故障隔离语义（防迟到写覆盖），但许可永不归还值得在文档标注。
- **P4** `zero-runtime/.../LifecycleCallBoundary.java:41`：挂起的 lifecycle 调用（超时中断后未完成）永久禁止该 runtime 后续生命周期操作，已获资源滞留至进程退出。同样是保守的隔离设计，非泄漏。
- **P5** `zero-data/.../InMemoryCrudRepository.java`：全方法 `synchronized` 单锁。定位为单进程原型/契约测试实现，生产走 Mongo/Redis/PG 适配器，符合分层设计；仅提醒勿在热路径误用。
- **P6** `zero-security/.../SecurityMetadataAssertion.java:17-27`：HMAC 断言无 keyId 字段，密钥轮换重叠期无法新旧并行验证。当前 digest 实现自述用于确定性本地测试，需轮换时再扩展。
- **P7** `zero-security/.../SecurityContextBridge.java:20-30`：ThreadLocal 上下文不跨线程池/异步回调传播，`current()` 静默为空——fail-safe 降权方向（无提权风险），但隐式依赖方排查成本高。可在 javadoc 补 withAsync 辅助说明。
- **P8** `zero-net/.../NettyProductionLifecycleSession.java:551-553`：replay provider 在 IO 线程（EventLoop）内联调用 `checkReplayAsync`，与鉴权（投递 `authenticationExecutor`）不一致，仅靠 javadoc 约束非阻塞；实现方误用即卡 IO 线程。建议与鉴权一致投递受管执行器。
- **P9** `zero-net/.../NettyProductionLifecycleSession.java:780`：`DRAINING` 状态在转移表中允许（ESTABLISHED→DRAINING）但全模块无 `transition(DRAINING)` 调用点，协议状态与实现脱节。建议实现优雅排空或删除该状态。
- **P10** `zero-net/.../NettyServerResources.java:19,43`：`ChannelGroup` 仅跟踪连接不设限，依赖 OS backlog 与调用方限流器（默认 permitAll）。建议提供 maxConnections 配置。
- **P11** `zero-net/.../NettyFrameChannelHandler.java:186-190`：`channelInactive` 中 `safeClose` 抛 ZeroException 时跳过 `fireChannelInactive`（line 190）；当前 handler 为管道末节点影响小，扩展管道时事件会丢失。建议 try-finally 包裹。

## 已核验无问题的关键防线（抽查结论）

- **Actor 调度**：64 段锁分片、蹦床续调（防 direct executor 栈溢出）、handoff 状态机（防 isDone/whenComplete 竞态）、执行超时隔离、容量双重准入（全局+每 lane）——逻辑闭环，无死锁/泄漏路径。
- **世界迁移协议**：request→prepare→commit→release 四阶段在 entity lane 串行，幂等重放（migrationId + sequence + watermark）、失败回滚（SOURCE_UNLOCKED 先于 ROLLED_BACK 发布）、路由纪元校验全部在位。
- **Runtime 装配**：拓扑串行 create、资源逆序关闭、build 失败全量回滚、双 ledger（started 组件 + build 资源）、single-use 状态机、重入关闭处理（callback 内 stop/close 不自等待）。
- **KCP**：SecureRandom conv 碰撞检测、32B 随机密钥用后清零、有界授权表（票据与连接共享容量）、ownership lease fencing 拒绝、TCP 控制通道认证 + 可选 TLS 强制。
- **Kafka RPC**：pending 表容量信号量 + token 防陈旧超时 + 时间轮 O(1) 移除 + close failAll + 关闭竞态双检（`KafkaRpcPendingRequests.java:203-234`）。
- **GM 授权链**：fail-closed 审批（缺省拒绝）、双重复核独立验证、目标参数与声明目标匹配、审计事件 REDACTED、策略未配置即拒绝（`GmOperationAuthorizer.java:96-97`）。
- **协议帧**：先验长后分配（`readBounded` 在拷贝前校验 maxExtension/maxPayload）、TCP `LengthFieldBasedFrameDecoder` 带 maxFrameLength、UDP 单包超限拒绝——无长度前缀 OOM 向量。
- **网络层资源与背压**（zero-net 全模块核查）：ByteBuf 引用计数无泄漏路径（guard 溢出/关闭均 release，入站由 Netty 解码器自动释放，出站 wrappedBuffer 随写释放）；IO 线程无模块内阻塞调用；半包/粘包由 `LengthFieldBasedFrameDecoder` 处理，长度字段无符号 4 字节无负数风险；TCP 出站双预算（连接 64MiB / 全局 256MiB OutboundBudget）+ writability 检查实现正确，WriteCompletion 无双重释放路径。
- **缓存规约 8 项能力**（AGENTS.md §14）：本地+远程双层、自动加载、写回（有界队列 1024 + 同键版本合并）、失效（含条件版本失效）、版本号（cacheVersion CAS + entityVersion）、防击穿（singleflight + maxConcurrentLoads）、防穿透（负缓存 negativeTtl）、熔断冷却——全部实现。
- **热更配置**：WatchService 静默去抖合并、OVERFLOW 兜底全表重载、关闭不等待自身、失败经 failureHandler 带 ErrorCode 上抛。

## 验证摘要

| 验证项 | 命令 | 结果 |
| --- | --- | --- |
| 全模块编译 | `mvnw -o -DskipTests compile`（JDK 21，61 模块） | exit 0 |
| 静态门禁 | `mvnw -o verify`（Checkstyle + PMD + SpotBugs） | exit 0，零告警 |
| 单元测试 | 本轮未运行（`-DskipTests`；审计为只读，未改动代码） | 不适用 |
| 子代理审计 | zero-security 18/18 文件；zero-net 49/49 src/main 文件 | 均已完成；zero-net 全部发现经主审计逐条证据核验（M2–M4/L5–L9/P8–P11） |

## 优先级建议

1. **M1+L4**：收紧 `TrustedProxyResolver.requireLiteral` 的 IPv6 结构校验并移除 `getByName`——一个文件内的小改动，消除 DNS 阻塞向量（顺带 GM 同模式清理）。
2. **M2**：admission 队列补字节预算——未认证连接的内存放大是当前最大攻击面。
3. **M3/M4**：TLS 握手可观测性与 onOpen 时序对齐——小改动，显著改善生产 TLS 运维与语义一致性。
4. **L1–L3/L5–L9**：安全工具类 allow-list 化、HTTP 入站预算、错误码语义修正等，可合并 1–2 个小 PR。
5. **P1/P2/P8**：持久化批选取结构、组合根默认冷却、replay 投递执行器化，属后续迭代项。

## 已知审计边界

- 子代理并行审计通道本轮大部分故障（security 与 net 两包成功返回并经核验；其余 8 个失败），其余模块由主审计就地精读+模式扫描覆盖；抽查级模块（见方法节）无静态告警与模式命中，但不排除极低概率的逐行级问题残留。
- benchmarks/scripts/examples 源集未纳入审计（非产品代码）；zero-net 的 src/test（14 个测试文件）未纳入本轮只读审计。
