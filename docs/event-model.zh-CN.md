# 事件模型设计

## 1. 目标

zeroServer 的业务开发以事件驱动和服务模块化为核心。业务程序员主要实现协议 DSL 生成的 BO 接口与实现模板，避免重复处理协议解析、线程切换、日志、错误码、返回封装和测试模板。

## 2. 事件类型

事件分为：

- 客户端协议事件：收到客户端请求并返回响应。
- 服务内部事件：模块内或模块间业务通知。
- 内部跨服与转发事件：框架内部跨节点转发。
- 业务跨服事件：业务层跨服调用。
- 运营后台事件：GM、配置、活动、数据修改。
- 定时事件：定时任务、周期 Tick、延迟任务。
- 数据变更事件：数据写入、脏标记、审计、数据中台。
- 异常与日志变更事件：错误、告警、日志处理。
- dev 指令事件：控制台或开发命令。

## 3. 业务接口命名

命名规则：

```text
XXXEventBO
XXXEventBOImp
```

S1-05 的 `.si` 主入口采用协议事件级命名：

```text
<Schema><Method>EventBO
<Schema><Method>EventBOImp
```

生成的协议分发器会先按协议号解码 payload，再调用对应 `XXXEventBO`。旧块式 DSL 仍可显式生成自定义 BO 名称。

示例：

```text
PlayerLoginEventBO
PlayerLoginEventBOImp
SceneMoveEventBO
SceneMoveEventBOImp
PlayerQueryPlayerEventBO
PlayerQueryPlayerEventBOImp
```

一个协议事件默认对应一个主业务实现。当某个 BO 过大时，应从业务职责上拆分事件，而不是继续堆积方法。

## 4. 返回模型

业务事件以异步返回为主，也允许同步返回结果。

设计要求：

- 同步结果适合本地轻量逻辑。
- 异步结果适合远程 IO、跨服调用、数据库操作。
- 业务异常默认不重试。
- 是否重试由业务代码或事件声明显式决定。

## 5. 事件总线能力

事件总线需要支持：

- 优先级。
- 拦截器。
- 重试。
- 死信。
- 幂等。
- 顺序保证。
- TraceId 传递。
- ErrorCode 绑定。
- 运行线程域声明。

线程域声明主要在核心基础层内部使用，以保障开发效率。业务层默认不需要手工处理线程切换。

当前阶段 1 已提供 `InMemoryEventBus` 作为本地确定性实现：

- 通过 `EventBus.register` 注册同一事件类型的处理器。
- 通过 `EventBus.addInterceptor` 注册拦截器。
- 处理器和拦截器均按优先级执行，优先级数值越小越早执行。
- 拦截器返回 `false` 时短路本次发布。
- 处理器异常会绑定 `ErrorCode` 后写入 `DeadLetterSink`。
- 本地实现不创建线程池，默认在调用线程中串行推进。

## 6. 顺序保证

顺序保证至少支持：

- 全局顺序。
- 玩家维度顺序。

后续可以扩展：

- 实体维度。
- 场景维度。
- session 维度。
- eventId 维度。
- customKey 维度。

## 7. 死信

死信落地必须可配置。

可选落地：

- 本地文件。
- Kafka。
- MongoDB。
- 自定义 Adapter。

死信事件必须包含：

- eventId。
- eventName。
- traceId。
- playerId 或 actor key。
- ErrorCode。
- 异常信息。
- 原始请求摘要。
- 重试次数。
- 发生时间。


## 2026-09-17 报告核实修订

内存总线按优先级顺序等待各处理器；单个处理器失败后记录死信并继续后续处理器，最终以首个异常及 suppressed 异常汇总失败。拦截器拒绝仍阻断整次发布。业务异常默认不自动重试。内存死信默认只保留最近 1024 条，可配置容量，通过 droppedCount 观测淘汰。
