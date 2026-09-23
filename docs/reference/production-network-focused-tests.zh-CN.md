# 生产网络生命周期测试索引

当前实现范围为 `minimum-slice / productionReady=false`。本页把历史 PNFT 编号映射到实际测试；测试存在不代表当前提交已执行，也不证明真实账号鉴权、TLS 轮换、容量或完整网关已经完成。

## 测试映射

| 历史编号 | 检查行为 | 实际测试入口 |
| --- | --- | --- |
| PNFT-01～PNFT-08、PNFT-10 | 握手超时、协议拒绝、鉴权、IO 线程边界、心跳、入站预算、限流、Actor 重连和本地兼容 | [ProductionNetworkLifecycleFocusedTest](../../zero-net/src/test/java/group/zn/zero/net/netty/ProductionNetworkLifecycleFocusedTest.java) |
| PNFT-09 | 低基数遥测标签与敏感信息边界 | [ProductionNetworkTelemetryObserverTest](../../zero-runtime-net/src/test/java/group/zn/zero/runtime/net/ProductionNetworkTelemetryObserverTest.java) |
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
