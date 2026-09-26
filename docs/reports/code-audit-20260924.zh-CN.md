# 源码审计（2026-09-24）

> 本文保留当时的只读审计候选，不是当前待修复清单，条目未全部经过运行复现。后续已有[9 月 24 日修复](../migrations/20260924-code-audit-20260924.md)。其中第 4 项引出的全局禁止 LOCAL 策略与既有 PAF1 按需装配契约冲突，并造成 10 项测试失败；已在[9 月 26 日质量基线修复](quality-baseline-20260926.zh-CN.md)中纠正。其余条目不能仅凭默认测试通过视为完成了专项验证。

- 范围：`src/main/java`。测试只用来排除已知修复，不作为缺陷来源。
- 方法：按并发调度、网络安全、数据一致性、游戏域与运行时装配四路只读核对，再对候选缺陷回读实现。未跑 Maven。
- 基线：工作区当前树。不声称覆盖全部 50+ 模块的每一行。
- 已排除、不再重复报告：Redis `saveIfVersion` 失败抛异常、Kafka RPC fail-closed 后 `commitSync`、`LayeredCacheService` 写入先 L2 后 L1、`InMemoryEventBus` 失败列表不可变、`SchedulerTimerRegistration` 初始 future 覆盖。
- 误报已剔除：`ExecutorActorScheduler.close()` 与异步挂起交错不会永久卡住 lane。队列空时 `drain` 会 `remove(laneKey, laneQueue)`，`close()` 清的是尚未出队的消息。

严重度：P0 可直接造成错误提交、权限形同虚设或生产数据丢失；P1 在明确条件下破坏一致性、鉴权或可用性；P2 边界或契约缺口。

## P0

### 1. Redis Cluster 条件写跨槽，失败仍写入本地 journal

`RedisDriverEnvelopeStore.saveIfVersion` 的 Lua 一次操作五个 key：对象、版本、bucket 索引、集合索引、journal。

- 前四个带 bucket hash tag，见 `DefaultRedisDataKeyStrategy.slotTag`。
- 集合索引是 `zero:indexes:{namespace:collection}`，见 `collectionIndexKey()`，与 bucket tag 不是同一个槽。

Cluster 上 `EVAL` 返回 `CROSSSLOT`。`catch` 在抛 `WRITE_FAILED` 之前调用 `appendLocal`。调用方按 journal 恢复时，会把从未写入 Redis 的版本重放成成功。`save()` 用 `version - 1` 走同一脚本，同样失败。

位置：`zero-data-redis/.../RedisDriverEnvelopeStore.java` 第 26–49、207–232、348–350 行。

### 2. 世界实体表跨 lane 并发写

`LocalWorldService` 注释写状态走 world/shard lane，但 `entities`、`migrations`、`events` 是普通 `HashMap`/`ArrayList`。

- `enterWorld` 走 world lane。
- `moveEntity` / `requestMigration` 走源 shard lane。
- `prepareMigration` / `commitMigration` 走目标 shard lane。
- `releaseSource` 走源 shard lane。
- `queryEntity`、`events()` 不进任何 lane，直接读共享表。

不同 lane 并行 `HashMap.put` 会丢条目或在扩容时死循环。`migrate()` 四步分别 `join`，步与步之间其它 shard 的任务可以插进来改同一实体。

位置：`zero-world/.../LocalWorldService.java` 第 21–25、38–47、56–57 行。

### 3. GM 默认审批只看调用方字符串

两参数 `GmOperationAuthorizer` 固定使用 `GmApprovalVerifier.contextState()`。`approvalRequired=true` 时，只要 `GmCommandContext.approvalState` 忽略大小写等于 `APPROVED` 就放行。`approvalToken`、`target`、过期、一次性消费都不参与。

`approvalState` 来自身份上下文。身份提供者若把请求体或 header 抄进该字段，审批开关无效。`GmApprovalState` 自己写明不做权限判定。内存 break-glass 有一次性消费，但端点没有调用它。

位置：`GmApprovalVerifier.java` 第 9–14 行；`GmOperationAuthorizer.java` 第 14–16、50–57 行。

### 4. 生产模式默认仍选中内存数据面，且不禁止 LOCAL

`productionBuilder().build()` 的 base 是 `LocalRuntime.module(...)`。`LocalRuntimePresets.local()` 固定 `select` 内存 cache、persistence、RPC，并 `contribute` 内存 repository。

`ProductionAssembly.composition` 在 `MODE_PRODUCTION` 下使用 `RuntimeProfile.production(Set.of())`。空集合不会对任何能力 `forbidKind(LOCAL)`。Redis、Mongo、PostgreSQL、Kafka 都是 `enabled=true` 才替换。不启用时进程以 production 名义跑内存实现，重启丢数据，启动不会失败。`REPOSITORY_SOURCES` 是多值绑定，启用 Redis 后内存 repository 仍可能留在列表里。

位置：`ZeroProductionRuntimeBuilder.java` 第 112–121 行；`ProductionAssembly.java` 第 184–187 行；`RuntimeProfile.java` 第 57–61 行；`LocalRuntimePresets.java` 第 25–38、63–68 行。

## P1

### 5. REST 必填幂等键没有进入端点

`GmRestTransportAdapter` 要求 body 含 `idempotencyKey`，放进 `GmTransportMetadata`。`GmOperationRequest` 用的是六参数构造器，内部把请求上的幂等键写成 `""`。

`BoundedGmTransportAdapter` 只核对 traceId 和来源 IP，然后 `endpoint.handle(request)`。端点只在 `!request.idempotencyKey().isBlank()` 时 claim。因此 REST 上的非 dry-run 命令没有去重。

位置：`GmRestTransportAdapter.java` 第 56–68 行；`GmOperationRequest.java` 第 14–21 行；`BoundedGmTransportAdapter.java` 第 16–23 行；`GmOperationEndpoint.java` 第 36–38 行。

### 6. 审计失败被记成可重试，前置审计可以绕开

`GmCommandExecutor.appendAudit` 在 `BEFORE_EXECUTE` 失败时抛 `GmAuditFailureException`，此时 handler 还没跑。该类继承 `ZeroException`。`GmOperationEndpoint.handle` 捕获全部 `ZeroException`，调用 `idempotencyStore.fail`。

`fail` 把状态写成 `FAILED`。下次 `claim` 对相同 fingerprint 返回 `FAILED`，端点不把 `FAILED` 当终态，于是重新授权并执行。审计恢复后，同一命令会在没有“先落审计再执行”的前提下跑完。`AFTER_EXECUTE` 失败同样被映射成拒绝，但业务已是 `COMMITTED`，执行器注释写明不得自动重试。

位置：`GmCommandExecutor.java` 第 172–179、292–294 行；`GmOperationEndpoint.java` 第 61–65 行；`InMemoryGmIdempotencyStore.java` 第 32–35、48–52 行。

### 7. Redis `findAll` 把对象 ID 再包一层 data key

索引集成员是 `snapshot.id()`（脚本 `ARGV[4]`）。`findAll` 把该成员交给 `findById`，`findById` 再 `dataKey(id)`。查找键变成 `zero:data:{slot}:zero:data:{slot}:<id>`，集合扫描全部 miss。单键读写不受影响。

位置：`RedisDriverEnvelopeStore.java` 第 156–164、306–308 行；脚本第 46 行。

### 8. 缓存失效先删 L1，并发读把旧 L2 填回

`invalidate` 先 `l1Cache.invalidate`，再异步删 L2。窗口内 `get` 读到旧 L2 并 `storeL1FromL2`。L2 删除完成后 L1 仍持有已作废值，直到 TTL。写入路径仍是先 L2 后 L1，未回归。

位置：`LayeredCacheService.java` 第 182–191 行。

### 9. 同步 RPC 超时只失败调用方，服务端继续提交

`RpcClientFactory.waitResult` 在 `get` 超时后返回 `REQUEST_TIMEOUT`，不取消 `transport.request`。服务端 handler 不看剩余 deadline，成功后照常应答；Kafka 路径在批次成功后 `commitSync`。非幂等方法会被调用方重试并再次执行。offset 没有提前提交。

位置：`RpcClientFactory.java` 第 290–297、340–354 行。

### 10. 帧推进在广播前失败会丢帧且输入不能重放

`FrameMatchRuntime.advance` 先 `frameNo.incrementAndGet()`，并从 `pending` 移除本帧输入、写入 `lastInputs`，然后才 `simulation.advance`、`events.publish`、`broadcaster.broadcast`。任一步抛异常，帧号已经前进，本帧不会出现在成功的 `FrameCommitted` 里。同一 `(uid, inputSeq)` 已在 `seenSequences`，重传在 `accept` 直接 return。`REPEAT_LAST` 还会在后续帧重复这份从未成功广播的输入。

位置：`FrameMatchRuntime.java` 第 86–90、120–141 行。

### 11. `tlsRequired` 在握手完成前检查，开启 TLS 的连接会被自己拒绝

`channelActive` 里注册 `handshakeFuture` 监听器，随后同步 `productionSession.start()`。`TLS_ESTABLISHED` 只在握手成功回调里写入。`start()` 看到 `tlsRequired && 属性不为 true` 就 `reject` 并关闭。合法 TLS 连接在握手完成前必然走这条拒绝。

位置：`NettyFrameChannelHandler.java` 第 119–141 行；`NettyProductionLifecycleSession.java` 第 125–133 行。

### 12. 生产网络默认鉴权放行；重放键不是 nonce

`ProductionNetworkPolicy.authenticate` 默认 `allow()`。只有包进 `SecurityNetworkPolicy` 才走 `SecurityChain`。`ProductionNetworkProvider` 在传入的 `securityChain == null` 时保留原始 policy，不包装；`resolve` 在启用 lifecycle 时会补 `failClosed()`，这条默认放行主要打到直接 new provider / 未走 resolve 的装配。

即便走了 `SecurityNetworkPolicy.checkReplayAsync`，nonce 是 extension 的摘要，sequence 是 `protocolId`，`issuedAt` 是服务器当前时间。空 extension 下同一协议 30 秒内所有包共享一个 nonce，改一个 extension 字节就是新 nonce。不能防重放。

位置：`ProductionNetworkPolicy.java` 第 41–46 行；`ProductionNetworkProvider.java` 第 115–121 行；`SecurityNetworkPolicy.java` 第 83–93 行。

### 13. UDP 无帧上限、无限流、无连接身份

每个 datagram 先整包拷贝再解码，不使用 `maxFrameLength`，不经过 production lifecycle，不占 `OutboundBudget`。连接 ID 是每个包 `UUID.randomUUID()`。伪造源或慢业务线程可以把执行器打满。

位置：`NettyUdpServer.java` 第 204–221、254–258 行。

### 14. 房间断线占坑，重连窗口方向反了；事件失败不回滚

`join` 把 `slot != LEFT` 都算占容。`leave` 是 `members.remove`，不会把槽改成 `LEFT`，所以断线成员一直占坑，满员后新人被拒绝。`reconnectable` 是 `now - disconnectedAt <= window`，没有 `now >= disconnectedAt`。时间回绕时差值为负，过期连接可以重进。

`start` / `settle` / `close` 先改状态再 `eventConsumer.accept`。消费者抛异常后状态已变，重试会得到 `room not ready` 或 `settlement already submitted`。

位置：`LocalRoomService.java` 第 115–136 行；`RoomMember.java` 第 11–12 行。

### 15. 排行榜在锁内、写 UID 表之前发事件，回调重入拆索引

`submitScore` 是 `synchronized`，同线程重入拦不住。`index.replace` 之后、`entries.put` 之前调用 `events.onEvent`。回调里再 `submitScore` 时，外层索引已是新节点，UID 表仍是旧条目，`replace` 的 `remove` 对不上，抛不一致或删错节点。

位置：`LocalRankingService.java` 第 73–79 行。

### 16. NPC tick 重入用旧快照覆盖；同 ID spawn 静默替换

`tick` 遍历调用前的副本。`decide` 或事件里再 `spawn`/`tick` 改过 `npcs` 后，外层仍按旧快照 `put`。`spawn` 不检查 `containsKey`，同 ID 直接覆盖，旧 NPC 没有 `DESPAWNED`。

位置：`LocalNpcZone.java` 第 9–11 行。

### 17. 世界事件失败被吞，迁移仍返回成功

`emit` 捕获 `eventSink` 的 `RuntimeException` 并忽略。`commitMigration` / `releaseSource` 已经改完归属或状态。监听方没收到 `MIGRATION_COMMITTED` / `MIGRATION_COMPLETED`，`migrate()` 仍返回成功。

位置：`LocalWorldService.java` 第 44–45、57 行。

### 18. HTTP 无在途上限，同名头后者覆盖

`NettyHttpServer` 在验签前把请求丢进 `handlerExecutor`。无 metadata 的请求直接进 handler。没有连接数或每 IP 在途限制。`headers.forEach` 写入 `Map`，同名头后者覆盖。安全头有“多于一个就拒绝”，其它头没有。

位置：`NettyHttpServer.java` 头折叠与投递路径（约 254–265、315–321、364–372 行）。

## P2

### 19. 出站预算在同步写失败时双次释放

`writeFrame` 同步抛错时 `WriteCompletion.finish` 把 `remaining` 减到 0 并 `release(bytes)`。`write()` 的 `finally` 再 `finish`，`remaining` 变负后再 `release` 一次。`used` 被减去两倍预留。之后 `acquire` 看到负占用，`maximum - previous` 变大，预算失效。

位置：`NettyOutbound.java` 第 99–120、152–162 行；`OutboundBudget.java` 第 12–20 行。

### 20. 相同 stateVersion、更大 syncSeq 的快照再次 APPLIED

`InMemoryStateSync.accept` 只在 `stateVersion < known.version()` 或 `syncSeq <= known.sequence()` 时忽略。版本相等且序号更大走 `APPLIED`，基线序号被改写。调用方把 `APPLIED` 当成状态变化就会重复覆盖。增量路径的基线校验是对的。

位置：`InMemoryStateSync.java` 第 24–32 行。

### 21. 来源 IP 在字面量校验前做 DNS 解析

`TrustedProxyResolver.resolve` 先 `InetAddress.getByName(source)`，再检查字符集。主机名会先解析。`GmSourceIpPolicy.of` 在构造期 `requireLiteral` 先于 `getByName`，配置侧顺序是对的；运行时 `allows` 仍对已通过空白检查的字符串直接 `getByName`。空 `allowedSourceIps` 明确是 `allowAll()`，只要求非空白。

位置：`TrustedProxyResolver.java` 第 24–32 行；`GmSourceIpPolicy.java` 第 16–18、27–37 行；`GmOperationAuthorizationPolicy.java` 第 20–21 行。

### 22. 玩家稳定 UID 用 hash 绝对值

`withStableHashUid` 使用 `Math.abs((long) accountId.hashCode())`。hash 碰撞的账号共用一个 UID，后登录覆盖先登录。`hashCode == Integer.MIN_VALUE` 时 `Math.abs` 仍为 `Integer.MIN_VALUE` 的 long 值，UID 为负。

位置：`LocalPlayerService.java` 第 137–138 行。

### 23. 场景进入重置坐标，移动会造出不存在的实体

再次 `enterScene` 无条件 `put` 坐标 `(0,0)`。`move` 对未见过的 uid `put` 新实体，不要求先进入。

位置：`LocalSceneService.java` 第 52–73 行。

### 24. 组件启动失败丢掉原因

`DefaultGameRuntime.startLifecycle` 捕获 `Throwable` 后抛 `RuntimeAssemblyException`，消息只有组件 ID，没有 `initCause` 或 `addSuppressed`。Redis/Kafka 连接失败的原始异常链在对外异常里消失。

位置：`DefaultGameRuntime.java` 第 180–188 行。

### 25. 协议字符串和字节数组没有独立于帧长的上限

`ZeroReader.readString` / `readByteArray` 只受剩余可读字节和帧 payload 上限约束。默认 payload 上限 16MiB，`ServerOptions.maxFrameLength` 只要求为正。集合分配前有按剩余字节的除法保护，整数溢出路径已挡住。没有解压实现。

位置：`zero-protocol/.../ZeroReader.java` 字符串与字节数组读取。

### 26. 重放检查失败会拆整条已认证连接

已认证连接上任一帧的 replay stage 超时、返回 null 或非 `ACCEPTED`，`NettyProductionLifecycleSession` 清空队列并关闭连接。一个坏帧可以踢掉主体会话。

位置：`NettyProductionLifecycleSession.java` replay 完成路径（约 503–524、608–634 行）。

## 读过但未列为缺陷

- `zero-event`：订阅者异常继续后续 handler，失败汇总，注册快照不可变。总线没有 close 状态。
- `zero-aoi`：`InMemoryAoiIndex` 离开会删观察者快照，重复 add 返回已存在。未再发现边界漏格。
- 排行榜排序与 `ADD` 溢出：同分后比 tieBreak 再比 uid；`Math.addExact` 拒绝且不改索引。问题只在锁内回调重入。
- `SecurityChain.failClosed`、HTTP metadata verifier：失败拒绝，未回归成放行。
- TCP 长度字段解码器使用 `maxFrameLength`。正常出站路径写前 `acquire`。
- 热更新单表先校验再发布。类注释不承诺多文件事务。CSV 不执行公式。
- 服务发现轮询用 `AtomicLong`，空列表抛 `SERVICE_NOT_FOUND`。

## 未验证

未执行 `mvn test`。上述结论来自控制流阅读，不是运行复现。Cluster `CROSSSLOT`、TLS 握手时序、RPC 超时后的服务端提交需要对应集成环境才能打出堆栈。
