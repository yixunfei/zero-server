# RPG TCP generated dispatcher 示例

本示例展示如何在不启动 Docker、不连接 Kafka / MongoDB / Redis / PostgreSQL / Nacos 的情况下，让真实 TCP 客户端请求进入 generated dispatcher：

```text
RpgTcp.si
  -> Maven generate-sources / zero-codegen
  -> DTO / codec / BO / GeneratedProtocolDispatcher
  -> NettyTcpServer
  -> Socket client
  -> ProtocolFrame / ZeroBinaryFrameCodec
  -> LogicSessionManager
  -> generated BO
```

## 1. 运行方式

先在仓库根目录安装当前 SNAPSHOT 工件：

```powershell
mvn -q -DskipTests install
```

然后运行示例：

```powershell
mvn -q -f examples/rpg-tcp-generated/pom.xml clean test
mvn -q -f examples/rpg-tcp-generated/pom.xml exec:java
```

输出类似：

```text
rpg-tcp=ok|protocol=97101|dispatched=true|uid=1001|trace=trace-rpg-tcp-1|channel=tcp-example|requests=1|handlerThread=zero-example-rpg-tcp|sessionsClosed=true
```

## 2. 示例说明

示例源码位于：

```text
examples/rpg-tcp-generated/src/main/protocol/RpgTcp.si
examples/rpg-tcp-generated/src/main/java/group/zn/zero/examples/rpgtcp/RpgTcpGeneratedApplication.java
```

它演示了以下做法：

- Maven `generate-sources` 阶段调用 `ProtocolCodegenCli`。
- 将 `target/generated-sources/zero-codegen` 加入示例编译源码目录。
- 使用生成的 `RpgTcpQueryPlayerProtocolDTO` 和 codec 构造 payload。
- 使用 `ProtocolFrame` 与 `ZeroBinaryFrameCodec` 作为 TCP 内部协议帧。
- 使用 `NettyTcpServer` 启动本地 TCP 服务端，监听 `127.0.0.1:0`。
- 使用 JDK `Socket` 作为真实 TCP 客户端发送请求。
- 使用 `LogicSessionManager` 记录连接对应的业务 session 和请求计数。
- 使用生成的 `GeneratedProtocolDispatcher` 调用手写 BO。
- 通过 `ZeroRuntimeExecutors.singleThreaded(...)` 取得受管逻辑执行器，关闭服务器后统一释放线程；业务不自行创建线程池。
- `zero-codegen` 只作为 Maven exec 插件依赖，不传递进业务编译/运行依赖。

## 3. 当前边界

本示例是 local / prototype 示例，不是生产网络模板：

- 不实现账号鉴权、握手、心跳、重连、限流或连接风控。
- 不实现 WebSocket、KCP 或生产 HTTP 路由。
- 不连接真实中间件。
- 不承诺生产性能、队列背压、连接容量或长稳表现。

生产网络接入需要单独设计连接治理、协议握手、鉴权、限流、观测、压测和故障演练。
