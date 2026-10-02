# 受管远程 IO 执行契约

更新日期：2026-10-01。适用版本：`0.1.2-SNAPSHOT`。当前状态：`planned`。

本文解决一个常见误区：方法返回 `CompletionStage`，不代表调用过程不会同步阻塞。游戏服务器必须把远程 IO 的执行线程、排队、超时、取消、重试和关闭边界写清楚，避免 Actor lane、Netty IO 线程和定时任务线程被数据库或消息系统拖住。

## 1. 适用范围

适用于：

- MongoDB、Redis、PostgreSQL。
- Kafka producer、consumer 和 RPC pending。
- Nacos 注册、查询和订阅。
- 远程 HTTP、TCP、KCP 控制面。
- 任何可能等待网络、磁盘、连接池或外部进程的调用。

不适用于：

- 纯内存计算。
- 已明确只在当前 Actor lane 内执行的短同步操作。
- Netty EventLoop 自身的非阻塞 channel 操作。

## 2. 调用分类

每个公共端口或 Adapter 方法必须标注下面一种执行类别：

| 类别 | 含义 | 可在 Actor lane 直接调用 |
| --- | --- | --- |
| `SYNC_FAST` | 有界、纯内存、不会等待外部资源 | 可以 |
| `SYNC_BLOCKING` | 可能等待磁盘、连接池或外部响应 | 不可以 |
| `ASYNC_NON_BLOCKING` | 调用本身不阻塞，完成由事件或回调驱动 | 可以，但完成后必须回 Actor |
| `ASYNC_EXTERNAL` | 依赖远程系统，可能产生未知结果 | 只能通过受管 remote IO gateway |

`CompletionStage` 只表示结果是异步可观察的；如果实现内部执行同步驱动调用，则仍属于 `SYNC_BLOCKING` 或 `ASYNC_EXTERNAL`。

## 3. 执行域

```text
Netty IO thread
  -> decode / admission / enqueue only

Actor lane
  -> state read/write only

Remote IO executor
  -> database / broker / HTTP client

Background executor
  -> audit / metrics / low-frequency maintenance
```

规则：

- Netty IO 线程不能执行数据库、Kafka、Redis 或同步 HTTP 调用。
- Actor lane 不能调用 `join()`、`get()` 或等待远程结果。
- remote IO executor 必须由 runtime/bootstrap 管理，业务代码不能自行创建线程池。
- 远程完成回调不能直接修改 Player、Room、Scene 或 World 状态，必须重新投递到所属 Actor lane。
- 每个执行域都必须有队列、in-flight 和关闭语义。

## 4. 最小调用流程

```text
Actor lane
  -> create immutable request
  -> submit to RemoteIoGateway
  -> queue admission
  -> external client call
  -> deadline / cancellation / result mapping
  -> completion callback
  -> dispatch result message to original lane
```

请求对象必须包含：

- requestId。
- traceId。
- owner or actor key。
- deadline / timeoutAt。
- operation name。
- idempotency key（如果操作会写入）。
- payload version。

## 5. 背压和预算

每个 remote IO gateway 至少需要三个预算：

| 预算 | 作用 |
| --- | --- |
| queue budget | 限制尚未开始的等待请求 |
| in-flight budget | 限制已提交但未完成的请求 |
| byte / payload budget | 限制请求和响应占用的内存 |

超限时必须产生明确结果：

- `REJECTED_OVER_QUEUE_LIMIT`
- `REJECTED_OVER_IN_FLIGHT_LIMIT`
- `REJECTED_PAYLOAD_LIMIT`

不能通过无限排队来隐藏数据库慢、Broker 积压或网络故障。拒绝结果应被业务映射成可重试、可降级或不可重试的 ErrorCode。

## 6. Deadline、超时和取消

每个外部请求必须有绝对 `timeoutAt`，不能只靠调用方本地计时器。消费端或 Adapter 收到过期请求后应拒绝执行。

必须区分：

- 本地等待超时。
- transport response 超时。
- 远端尚未开始执行。
- 远端已经开始执行但本地取消。
- 远端执行结果未知。

取消的最小保证是：

- 释放本地 pending、队列和回调资源。
- 不再向业务层发布过期结果。
- 如果底层驱动支持，则传播取消。
- 不假设远程业务已经回滚。

对于写操作，本地取消后必须进入幂等查询、对账或补偿路径，不能简单重试原请求。

## 7. 重试规则

默认不重试业务异常。重试必须显式声明：

- 操作是否幂等。
- 最大尝试次数。
- 最大总耗时。
- 退避策略。
- 是否允许在新 owner 上重试。
- 是否需要状态版本或 epoch 检查。

以下情况默认禁止透明重试：

- 扣款。
- 发奖。
- 结算。
- owner 迁移中的状态写入。
- 返回结果未知的外部写入。

## 8. 未知写结果

以下情况都可能产生未知写结果：

- 连接在服务端确认后断开。
- 客户端超时但 Broker 已接收。
- 数据库写入完成但响应丢失。
- RPC response 已发送但调用方关闭。

统一处理流程：

```text
UNKNOWN
  -> query by idempotency key
  -> CONFIRMED / NOT_FOUND / STILL_UNKNOWN
  -> compensate or manual review
```

业务不能用“再次写入”代替未知结果恢复。

## 9. 关闭顺序

运行时关闭必须遵循：

```text
stop new submissions
  -> mark gateways draining
  -> reject new queue entries
  -> wait bounded in-flight
  -> cancel local pending
  -> close Adapter clients
  -> release executors
```

关闭等待必须有上限。超时后应报告未完成请求、队列数量、资源名称和底层原因，而不是无限等待。

## 10. Adapter 契约要求

每个真实 Adapter 的文档和测试都应回答：

1. 调用是否同步阻塞。
2. 使用哪个执行域。
3. 最大请求和响应大小。
4. timeoutAt 是否传到远端。
5. cancellation 能否传到驱动。
6. 哪些错误可重试。
7. 哪些写操作可能未知结果。
8. 关闭时如何处理 in-flight。
9. 是否支持健康检查和恢复。
10. 是否需要外部幂等或 fencing。

## 11. 验收用例

- Actor lane 在远程调用未完成时仍能处理另一条 lane 的消息。
- remote IO 队列达到上限后稳定拒绝，不发生无界内存增长。
- deadline 过期请求不会在消费端继续执行。
- 本地取消会清理 pending 和回调。
- executor 拒绝、连接关闭和 Adapter 异常都能释放预算。
- 远程写返回未知结果时不会透明重复写。
- runtime 关闭后，保留的 Repository、Cache、RPC handle 会返回明确的 closed 错误。
- 所有拒绝、超时、取消和恢复都带有 ErrorCode、traceId 和低基数指标。

## 12. 交付要求

后续新增或修改 Adapter 时，必须同步：

- 更新模块用户指南。
- 更新 `docs/data-cache.zh-CN.md` 或 `docs/rpc.zh-CN.md`。
- 在代码注释中标明线程安全和阻塞语义。
- 增加 queue、timeout、cancellation、close 测试。
- 对真实外部系统增加隔离验收记录。
- 在能力矩阵中区分“有 Adapter”和“已完成恢复/容量证据”。
