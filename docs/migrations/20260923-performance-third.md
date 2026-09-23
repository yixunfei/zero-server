# 2026-09-23 第三轮性能与网络资源治理迁移

适用 `0.1.0-SNAPSHOT` 开发阶段。没有协议线格式或持久化格式迁移。
实测采用范围、失败候选和验证证据见[第三轮报告](../reports/performance-third-20260923.zh-CN.md)。

## 业务接入

- 单帧发送直接进入有界出站路径，业务继续调用 `send` / `sendFrame` / `sendFrames`。
  Future 成功同时要求全部写成功且本次 flush 调用成功；调用者取消不会撤销已准入写，也不会提前释放预算。
- AOI 首条变化减少临时集合，返回事件仍独立且不可变；排序、scene/sync 序号、异常重试和观察者释放契约不变。
- 默认 Actor ID 保留随机 UUID 前缀和 36 进制序号，显式 ID/trace 原样传递。业务没有迁移步骤。
- EventBus 的同步成功结果为只读 `CompletionStage<Void>`。使用接口组合方法，或者通过
  `stage.toCompletableFuture()` 获得调用方独立 Future；不要强转返回值后调用 CompletableFuture 的可变方法。
  对转换结果的取消、`obtrudeValue`、`obtrudeException` 不影响总线和其他调用者。

## IO 资源拥有权

默认 TCP/HTTP/UDP 服务器通过 `NettyIoResources` 创建独占组，沿用既有构造和 `ServerOptions` 配置。
无需业务自行管理线程。AUTO 仍使用 NIO；显式 EPOLL 不可用时立即失败，UDP 也使用匹配的 datagram 传输。

组合根需要共享时，创建并拥有 `NettyIoResources.open(options)`，通过服务器新增构造参数注入。
server 只拥有自己的监听 channel 和已接受连接；`server.stop()` 关闭这些 channel，但不关闭借用 IO 组。
先停止所有借用服务器，再关闭 IO 资源。每个 TCP server 的出站总预算仍独立计算。

运行时组合可显式安装 `NetworkRuntime.ioModule(options)`，或注册 `NetworkRuntime.ioResources(options)`
并选择 `NetworkRuntime.IO_RESOURCES`。server provider 声明 `require(IO_RESOURCES)`，取得资源后构造服务器，
将服务器作为 contribution lifecycle 登记。运行时沿依赖逆序停止服务器，再通过 ResourceRegistrar 释放 IO 组；
后续创建失败时也会回滚。最小 bootstrap 不引入 Netty，默认 local catalog 不强制安装此可选资源。

资源关闭时，普通线程有界等待；从该资源 EventLoop 发起时只请求关闭，不等待自身终止。
需要确认实际线程退出的组合根，可在其他线程等待 `io.termination()`；该信号的取消不会取消资源关闭。
`io.snapshot()` 提供有效 transport、boss/worker 线程数、关闭和终止状态。

## 失败处理变化

绑定异常现在统一为 `ZeroException`，错误码 `NetErrorCode.START_FAILED`，底层 `BindException` 保留为 cause。
原来直接捕获 `BindException` 的调用方应改为捕获 `ZeroException` 并按错误码处理。
这也修复了 checked bind 异常越过原 RuntimeException 清理分支的问题。

没有调整 backlog、flush、GC 或出站预算默认值。部署选择必须参考真实业务负载和对应平台实测，
Windows 回环结果不能替代 Linux EPOLL、TLS 生产容量或跨机容量。
