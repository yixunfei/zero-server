# zeroServer 生产网络生命周期契约

本文最初是 `production-network-lifecycle-contract` 的只读确认草案。用户已于 2026-07-10 确认方案 B 与 1–7 默认边界，首个 TCP 最小运行时切片已按该边界实现。

状态：`minimum-slice-implemented / confirmed=true / productionReady=false`

当前实现边界：

- `zero-net` 已提供独立 opt-in 配置、状态机、策略端口、限流 SPI、observer、Netty 会话门控与标准 `NetErrorCode`。
- `ZeroServerTcpApplication` 提供本地 starter 的显式 `start`/`probe`/`stop` 生命周期门面；真实 loopback 请求、线程归属、端口冲突和清理证据见 `target/acceptance-evidence/` 及迁移说明 `20260914-starter-template-tcp-lifecycle.md`。
- `zero-server-starter-production` 已提供显式配置解析、默认有界每 IP 限流器、日志/指标 observer 与受管执行器组合。
- `ProtocolFrame`、协议 ID、DSL、codegen、generated dispatcher、`ServerOptions` 构造和 local/prototype 默认行为均未改变。
- PNFT-01～PNFT-10 已落为 JUnit focused tests；映射见 `docs/reference/production-network-focused-tests.zh-CN.md`。
- 本切片仍不代表完整生产网关或 production ready；真实鉴权、TLS/WAF/DDoS、容量/长稳验证和其他传输生命周期仍需独立设计与验证。
- 后续任何公共 API、协议、线程模型、日志字段、指标标签、ErrorCode 或模块依赖方向变更，都应先通过 GitHub Design Proposal 说明兼容、性能、安全和验证边界，并等待维护者评审。

## 1. 目标

生产网络入口要为多类型游戏服务器提供统一底座，至少覆盖：

- 连接接入与关闭。
- 握手与协议版本检查。
- 鉴权成功、鉴权失败和匿名拒绝。
- 心跳、超时和连接保活。
- 限流、背压和拒绝策略。
- 重连窗口与旧连接处理。
- TraceId、连接日志、安全日志、错误日志和指标。
- IO 线程与业务 Actor 线程边界。

该契约的第一阶段最初只准备最小确认口径；该阶段随后已按确认边界完成 focused tests 和最小实现，但仍未一次性扩展为完整生产网关。

实际测试入口和运行命令见[生产网络测试索引](production-network-focused-tests.zh-CN.md)。当前安全链补充见[入口安全迁移说明](../migrations/0.1.0-p0-2-entry-security.zh-CN.md)。下文保留首个生命周期切片的设计边界；最新安全能力以迁移说明和源码为准。

## 2. 非目标

- 不实现真实账号、渠道、token 或第三方鉴权。
- 不引入 TLS、WAF、DDoS 防护或完整安全网关。
- 不改变当前 TCP generated 示例的 local/prototype 定位。
- 不改变业务协议 DSL 或 codegen 规则。
- 不在 Netty IO 线程中执行业务阻塞逻辑。
- 不承诺生产容量、压测结果或 SLA。

## 3. 候选状态机

本节标题为兼容历史 readiness gate 保留“候选”字样。下表已经成为 minimum slice 的 `ConnectionLifecycleState` 实现基线；未来改变状态语义仍需重新确认。

| 状态 | 含义 | 允许进入方式 | 允许离开方式 |
| --- | --- | --- | --- |
| `ACCEPTED` | Socket / Channel 已被接入，但还没有完成业务握手 | Netty accept | 收到握手、握手超时、底层关闭 |
| `HANDSHAKING` | 正在校验协议版本、客户端能力和基础参数 | 收到握手帧 | 握手通过、握手拒绝、握手超时 |
| `AUTHENTICATING` | 正在校验用户身份或临时会话 | 握手通过且需要鉴权 | 鉴权通过、鉴权拒绝、鉴权超时 |
| `ESTABLISHED` | 连接已可承载业务协议 | 鉴权通过或被允许匿名进入 | 心跳超时、限流关闭、重连替换、主动关闭、异常关闭 |
| `DRAINING` | 连接正在排空或优雅关闭 | 主动下线、重连替换、停服准备 | 关闭完成、排空超时 |
| `REJECTED` | 连接被明确拒绝 | 握手失败、鉴权失败、限流拒绝、版本不兼容 | 关闭完成 |
| `CLOSED` | 连接生命周期结束 | 任意关闭路径 | 无 |

原则：

- 状态迁移必须单调、可观测、可测试。
- 任何连接只能有一个当前生命周期状态。
- 连接拒绝必须有明确原因、ErrorCode 分类、日志和指标。
- 跨 Actor 的玩家状态恢复和旧连接踢下线必须通过消息，不允许跨线程直接改玩家状态。

## 4. 候选事件

| 事件 | 触发来源 | 处理原则 |
| --- | --- | --- |
| `channelAccepted` | Netty IO | 只登记轻量连接上下文，不执行业务阻塞逻辑 |
| `handshakeReceived` | 客户端帧 | 校验协议版本、客户端能力和基础字段 |
| `handshakeTimeout` | 定时器 | 拒绝连接并记录安全/错误日志 |
| `authRequested` | 握手后 | 可投递到异步远程 IO 或虚拟线程，不阻塞 IO 线程 |
| `authSucceeded` | 鉴权回调 | 建立业务 session，并把后续业务投递到对应 Actor |
| `authRejected` | 鉴权回调 | 拒绝连接，绑定权限或协议类 ErrorCode |
| `heartbeatReceived` | 客户端帧 | 更新连接活跃时间，不触发重业务逻辑 |
| `heartbeatMissed` | 定时器 | 达到候选阈值后关闭或进入 draining |
| `rateLimitExceeded` | 入口限流器 | 先拒绝新增请求，严重时关闭连接 |
| `reconnectRequested` | 新连接握手/鉴权 | 经玩家 Actor 确认后替换旧连接 |
| `closeRequested` | 服务端或客户端 | 进入 draining 或直接关闭 |
| `channelError` | Netty IO | 记录错误原因，关闭连接，避免继续执行业务逻辑 |

## 5. 候选默认参数

本节标题为兼容历史 readiness gate 保留“候选”字样。以下数值已成为显式 opt-in minimum slice 的可配置默认值，但不是生产 SLA、容量结论或部署建议。

| 参数 | 候选值 | 说明 |
| --- | --- | --- |
| 握手超时 | 5 秒 | 从 `ACCEPTED` 到 `HANDSHAKING` 完成 |
| 鉴权超时 | 10 秒 | 从 `AUTHENTICATING` 到成功或拒绝 |
| 心跳间隔 | 15 秒 | 客户端建议发送周期 |
| 心跳丢失阈值 | 2 次 | 超过阈值后关闭或进入 draining |
| 重连窗口 | 30 秒 | 玩家 Actor 保留旧 session 的候选窗口 |
| 单连接入站 frame 预算 | 1024 | `ProductionNetworkConfig` 默认值，可按业务显式调整 |
| 单 IP 新建连接速率 | 20 / 秒，突发 40 | 仅属于 production starter 示例策略；有界槽默认 16384 |

## 6. 线程与执行域边界

- Netty IO 线程只做 frame 读取、轻量校验、连接状态推进和投递。
- IO 线程不允许执行数据库、Redis、Kafka、Nacos、HTTP 鉴权或其他不可控远程 IO。
- 鉴权如果需要远程调用，必须进入框架统一管理的远程 IO 执行域或低频虚拟线程执行域。
- observer 事件通过每连接私有的有序 drain 提交到框架共享受管 executor；同一连接最多存在一个活动 drain，并按会话提交顺序执行，不要求共享 executor 自身为单线程。
- 不同连接可以在共享 executor 上并发观测；有序 drain 不创建线程或线程池，也不改变 executor 生命周期。
- 当前 observer 待执行队列没有独立容量上限、背压或丢弃策略；慢 observer 可能积压，不能据此宣称遥测容量、长稳或故障降级已经验证。
- 玩家在线状态默认绑定 player actor。
- 场景状态默认绑定 scene actor。
- 重连、踢下线和 session 替换必须通过 Actor 消息完成。
- 限流、背压和关闭路径必须可观测，不允许只打印日志后继续处理。

## 7. ErrorCode 分类候选

本节标题为兼容历史 readiness gate 保留“候选”字样。minimum slice 已在 `NetErrorCode` 中实现握手、协议版本、鉴权、心跳、入站溢出、限流、重连、非法状态和 observer 失败错误码；下表保留分类设计依据。

| 分类 | 场景 |
| --- | --- |
| 协议错误 | 握手格式错误、协议版本不兼容、非法 frame |
| 权限错误 | 鉴权失败、token 失效、匿名访问被拒绝 |
| 系统错误 | 连接状态异常、内部执行失败、关闭失败 |
| 缓存或数据错误 | session 恢复依赖缓存或数据读取失败 |
| 限流错误 | 单连接、单 IP、单玩家或全局入口限流 |

后续扩展问题：

- 是否新增独立的网络连接 ErrorCode 段。
- 是否复用协议错误和权限错误的现有分类。
- 错误是否需要返回客户端，还是只写安全日志。

## 8. 日志字段候选

本节标题为兼容历史 readiness gate 保留“候选”字样。当前 `ConnectionLifecycleObservation` 与 production telemetry observer 已实现 minimum slice，并通过 O1 `LogAppender / LogPipeline` 统一字段运行时写入安全日志。日志可以包含高基数字段，但必须经过字段预算、拒绝/脱敏和双安全门；production 采样、异步落地、背压和容量证明仍由后续切片负责。

| 字段 | 适用日志 | 说明 |
| --- | --- | --- |
| `traceId` | 全部 | 入口创建或继承 |
| `connectionId` | 连接、错误、安全、性能 | 连接级排障使用 |
| `remoteAddress` | 连接、安全 | 需要脱敏或按配置输出 |
| `playerId` | 连接、行为、安全 | 未鉴权时为空 |
| `state` | 连接、错误、性能 | 当前生命周期状态 |
| `event` | 连接、错误、安全 | 生命周期事件 |
| `reason` | 错误、安全 | 拒绝、关闭或异常原因 |
| `errorCode` | 错误、安全 | 对外错误必须绑定 |
| `latencyMillis` | 性能 | 握手、鉴权、投递耗时 |

安全要求：

- 不输出 token、密码、完整密钥或敏感连接串。
- 鉴权失败、限流拒绝和异常高频连接必须进入安全日志。
- 错误日志必须绑定 ErrorCode。

## 9. 指标标签候选

本节标题为兼容历史 readiness gate 保留“候选”字样。表中指标及低基数标签已用于 minimum slice telemetry；默认禁止将 `playerId`、`connectionId`、完整 IP、token、房间 ID、场景 ID 或 traceId 作为 Prometheus label。

| 指标候选 | 类型 | 允许标签 |
| --- | --- | --- |
| `zero_net_connections_active` | gauge | `listener`、`protocol` |
| `zero_net_connection_events_total` | counter | `listener`、`protocol`、`event`、`result` |
| `zero_net_connection_rejections_total` | counter | `listener`、`protocol`、`reason` |
| `zero_net_handshake_latency_seconds` | histogram | `listener`、`protocol`、`result` |
| `zero_net_auth_latency_seconds` | histogram | `listener`、`protocol`、`result` |
| `zero_net_heartbeat_timeouts_total` | counter | `listener`、`protocol` |
| `zero_net_rate_limited_total` | counter | `listener`、`protocol`、`scope` |

后续统一可观测性问题：

- `listener` 是否表示端口、业务入口名或逻辑网关名。
- `protocol` 是否允许区分 tcp / udp / websocket / http。
- `reason` 和 `scope` 的枚举数量上限是多少。

## 10. Focused Tests 候选

本节标题为兼容历史 readiness gate 保留“候选”字样。以下 PNFT-01～PNFT-10 已实现并通过；未来扩展必须继续保留这些回归：

- 握手超时后连接关闭，并记录 ErrorCode、日志和拒绝指标。
- 协议版本不兼容时拒绝连接，不进入业务 Actor。
- 鉴权失败时拒绝连接，安全日志包含 traceId、connectionId、reason 和 ErrorCode。
- 鉴权耗时不阻塞 Netty IO 线程。
- 心跳丢失达到阈值后关闭连接，指标计数增加。
- 单连接入站队列超限时触发背压或拒绝。
- 限流拒绝不执行后续业务协议。
- 重连替换旧连接必须通过 player actor 消息。
- 指标标签不包含 playerId、connectionId、完整 IP 或 token。
- local/prototype TCP 示例在未启用 production profile 时行为保持兼容。
- 延迟或多线程共享 observer executor 下，同一连接的 accept、状态变更与 close 仍按提交顺序执行，且只提交一个活动 drain。

## 11. 高风险确认包

本节是 2026-07-10 实施前的历史确认记录，用于解释为什么 minimum slice 需要暂停。它不表示当前仍在等待同一切片的确认。

为什么高风险：

- 握手、鉴权、心跳、限流和重连会影响协议兼容、客户端行为和生产安全。
- 连接生命周期会影响线程模型、Actor 投递、资源释放和故障恢复。
- ErrorCode、日志字段和指标标签一旦发布需要长期兼容维护。

影响模块：

- `zero-net`
- `zero-protocol`
- `zero-core`
- `zero-log`
- `zero-monitor`
- `zero-actor`
- `zero-game`
- `zero-server-starter`
- `zero-server-starter-production`
- `examples/rpg-tcp-generated`

可能破坏的行为：

- 当前 TCP generated 示例可能需要适配新生命周期。
- 错误心跳默认值可能造成误踢、重连风暴或资源泄漏。
- 错误限流默认值可能影响正常玩家或无法拦截异常连接。
- 指标标签过多可能导致高基数和监控性能问题。
- 日志字段变化可能影响排障、审计和后续数据消费。

历史备选方案：

- 方案 A：只保留本草案和确认包，不做运行时实现。
- 方案 B：先冻结最小契约和 focused tests，再进入最小实现。
- 方案 C：直接实现完整生产网络治理。该方案风险面过大，不推荐。

历史推荐与决策：

用户已确认方案 B、默认边界和 focused tests，并完成独立实现与验证。实现保持 local 默认无外部依赖，没有改变 `ProtocolFrame`、协议 ID、`ServerOptions` record components 或模块依赖方向。

历史确认前需要用户确认的问题（原文保留）：

- 是否允许新增连接生命周期、握手、心跳、限流相关公共 API 或配置键。
- 是否必须兼容当前 TCP generated 示例 frame 与 dispatcher 行为。
- 鉴权失败、握手失败、心跳超时和限流拒绝是否返回客户端错误。
- ErrorCode 是否新增网络连接分类，还是复用协议/权限/系统分类。
- 指标标签允许哪些维度，哪些维度必须禁止。
- 重连替换旧连接是否必须经过 player actor。
- 本轮是否只允许设计文档和 focused tests，不进入运行时实现。

当前再次暂停问题只针对真实鉴权、TLS、WAF / DDoS、完整网关、多传输、统一 EventLoop 管理、容量和长稳等未覆盖扩展。

## 12. 后续推进顺序

```text
ZeroProductionNetworkFocusedTestPlan
  -> network focused Maven tests
  -> root reactor test / quality
  -> production profile integration tests
  -> isolated external environment tests
  -> GitHub Design Proposal for public-contract changes
```

当前网络 minimum slice 状态是 `minimum-slice-implemented / productionReady=false`。任何未覆盖扩展都应单独评估设计、兼容性、性能、安全和验证范围；合入公共契约前必须经过维护者评审。
