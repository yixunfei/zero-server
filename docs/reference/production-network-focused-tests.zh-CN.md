# 生产网络生命周期测试索引

当前实现范围为 `minimum-slice / productionReady=false`。本页把历史 PNFT 编号映射到实际测试；测试存在不代表当前提交已执行，也不证明真实账号鉴权、TLS 轮换、容量或完整网关已经完成。

## 测试映射

| 历史编号 | 检查行为 | 实际测试入口 |
| --- | --- | --- |
| PNFT-01～PNFT-08、PNFT-10 | 握手超时、协议拒绝、鉴权、IO 线程边界、心跳、入站预算、限流、Actor 重连和本地兼容 | [ProductionNetworkLifecycleFocusedTest](../../zero-net/src/test/java/group/zn/zero/net/netty/ProductionNetworkLifecycleFocusedTest.java) |
| PNFT-09 | 低基数遥测标签与敏感信息边界 | [ProductionNetworkTelemetryObserverTest](../../zero-runtime-net/src/test/java/group/zn/zero/runtime/net/ProductionNetworkTelemetryObserverTest.java) |
| no-op 快路径 | 默认 observer 在握手、业务处理和关闭时均不提交观测任务 | `ProductionNetworkLifecycleFocusedTest.noOpObserverShouldAvoidExecutorSubmission` |
| 最小可定制化 | 无日志/监控装配、无隐式限流/安全链、显式 observer、心跳配置校验 | [NetworkRuntimeTest](../../zero-runtime-net/src/test/java/group/zn/zero/runtime/net/NetworkRuntimeTest.java) |
| 生产便利入口 | 默认心跳关闭、自定义限流/observer、依赖闭包与 typed config | [ProductionNetworkProviderTest](../../zero-server-starter-production/src/test/java/group/zn/zero/starter/production/ProductionNetworkProviderTest.java) |
| 真实 TCP 示例 | Socket 收发、generated dispatcher、BO 与执行器关闭 | [RPG TCP 测试](../../examples/rpg-tcp-generated/src/test/java/group/zn/zero/examples/rpgtcp/RpgTcpGeneratedApplicationTest.java) |
| 安全策略与 listener | TCP/HTTP 实例、显式安全链和连接处理 | [NettyServerImplementationsTest](../../zero-net/src/test/java/group/zn/zero/net/netty/NettyServerImplementationsTest.java) |

## 运行

在仓库根目录使用 Java 21：

```bash
mvn -B -ntp -pl zero-net,zero-runtime-net,zero-server-starter-production -am test
mvn -B -ntp -DskipTests install
mvn -B -ntp -f examples/rpg-tcp-generated/pom.xml test exec:java
```

单测使用可控时钟、内存连接、鉴权/限流替身和 Actor 消息端口；TCP 示例使用本机临时端口，不连接外部中间件。扩展时需继续验证状态、ErrorCode、线程归属、资源关闭和安全日志，不能只断言请求成功。

生命周期和下一步安全边界见[生产网络契约](production-network-lifecycle-contract.zh-CN.md)，完整网关与真实恢复工作见[优化路线图](../optimization-roadmap.zh-CN.md)。

最小可定制化回归还覆盖关闭心跳后的握手/鉴权超时、迟到鉴权不重开连接、入站队列溢出，以及显式安全链的鉴权拒绝和重放拒绝。PNFT-07 先确认建立连接再拒绝业务帧，避免仅测试到握手限流。
# 2026-09-28 报告核实补充

focused 测试覆盖：TCP 默认工厂 fail-fast、production handshake/frame 限流、UDP 地址 peer 复用与空闲回收、超长包计数/丢弃后继续收包。原 KCP 占位测试已由 `zero-net-kcp` 中真实 TLS/UDP、认证和故障测试替代，见 [KCP 验证说明](../migrations/20260928-kcp-support.md)。这些本地测试不代表 WAF/DDoS、普通 UDP 可靠性或公网容量已经通过。
