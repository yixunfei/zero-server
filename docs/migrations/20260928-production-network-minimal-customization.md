# 生产网络生命周期最小可定制化迁移说明

## 变更

production network lifecycle 只提供生命周期状态机、握手/鉴权超时、入站 frame 预算和失败关闭。限流、安全链、观测和心跳检查不再隐式启用：调用方通过 builder 或 `NetworkRuntime.module(...)` 显式注入所需实现。

## 行为变化

- 未传 `NetworkRateLimiter`：使用 permit-all，不限制新连接或业务帧频率。
- 未传 `SecurityChain`：直接执行调用方的 `ProductionNetworkPolicy`，不会额外包装安全链。
- 未传 observer：使用 no-op observer，即使已安装日志/监控模块；显式 observer 不要求这些模块。
- no-op observer 不分配观测对象或单连接观测队列，不调用 observer executor；握手、业务门控、超时和关闭仍正常执行。已有 `ConnectionLifecycleObserver.noOp()` 调用无需迁移，自定义空回调仍按显式 observer 调度。
- 心跳检查默认关闭；设置 `zero.net.lifecycle.heartbeat-enabled=true` 或使用启用心跳的 `ProductionNetworkConfig` 才会调度心跳检查。
- 握手/鉴权超时、入站队列预算和异常关闭仍由框架执行。

## 升级步骤

需要连接限流、TLS/重放安全、日志/指标或心跳的部署，升级时显式配置对应实现；不能依赖旧版 production starter 的默认每 IP 限流或默认安全链行为。

1. 最小装配使用 `NetworkRuntime.module(policy, null)`；policy 必须由应用提供。policy 的默认 `authenticate` 允许连接，要求登录的应用必须覆盖该方法或显式传入 `SecurityChain`。
2. 定制装配使用 `NetworkRuntime.module(policy, limiter, securityChain, observer)`，或生产 builder 上的 `networkRateLimiter`、`securityChain`、`networkObserver`。策略实现必须线程安全，限流与轻量握手检查不得阻塞 IO 线程。
3. 移除旧配置 `zero.net.lifecycle.per-ip-permits-per-second`、`zero.net.lifecycle.per-ip-burst-capacity`、`zero.net.lifecycle.rate-limit-slots`。这些键及其 Java 常量已删除，runtime 不再消费，限流阈值由应用自己的实现配置。私有默认 IP 限流类已删除，不增加兼容层。
4. `ProductionNetworkConfig` record 构造新增末位 `heartbeatEnabled` 参数；直接构造调用需显式传入。`defaults(...)` 关闭检查，`withHeartbeat(interval, missed)` 同时设置参数并启用检查；`withHeartbeatEnabled(false)` 关闭检查并保留参数。
5. 配置键 `zero.net.lifecycle.heartbeat-enabled` 默认 false，显式值只接受不带空格的 true/false（大小写不敏感）；非法值在 planning 时失败。仅设置间隔不会启用检查。心跳帧识别仍由 `policy.isHeartbeat` 决定，与超时检查独立。
6. 直接调用 `ProductionNetworkLifecycle` 的安全链构造器与 runtime 入口统一执行显式安全链，无需调用方再包装 `SecurityNetworkPolicy`。省略安全链时 `securityChain()` 返回 null，调用方需处理空值。
7. `zero-runtime-net` 不再传递依赖 `zero-runtime-log` / `zero-runtime-monitor`；保留的 `ProductionNetworkTelemetryObserver` 是可选工具，其 `zero-log` / `zero-monitor` 依赖标为 optional。使用该工具的消费者需显式声明这两个模块并注入已有 `LogAppender` 与 `MetricRegistry`；也可仅实现中立 observer。

## 脚手架与业务边界

脚手架适合生成启用开关、策略注入点和受管执行器装配。玩家/账号/协议维度限流放在应用的 `NetworkRateLimiter`；连接属性中的可信主体可作为键。共享/分布式计数与清理策略由应用决定，不在通用脚手架中固定 Redis 或 IP 桶实现。

TCP 粘包/拆包属于已有长度帧解码；重发、请求幂等、断线后会话恢复属于应用协议语义。`coordinateReconnect` 保留 Actor 消息协调端口，框架不自动重放有副作用请求，也不代替客户端重连退避策略。

## 验证与回滚

Java 21 下已通过 `ProductionNetworkLifecycleFocusedTest`（19 项）、`NetworkRuntimeTest`（7 项）、`ProductionNetworkProviderTest`（9 项），覆盖 opt-in 行为、显式安全链拒绝和关闭心跳后的超时/预算保护。完整模块质量结果记录于本任务 VERIFY；这些验证不等于整个未提交工作区或生产容量的发布证据。若应用尚未配置必需策略，应在启用前完成注入，或回退升级前版本及配置。
