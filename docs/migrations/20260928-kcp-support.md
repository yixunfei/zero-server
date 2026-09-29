# 2026-09-28 KCP 支持迁移

适用于 `0.1.0-SNAPSHOT` 开发阶段。新增独立 `zero-net-kcp` 模块，复用现有业务协议帧；业务协议 ID 和 TCP 线格式不变。

这是首轮服务端闭环的历史迁移记录。同日后续已新增场景配置与受管客户端，并调整默认帧上限；
当前接入请继续阅读[多场景优化迁移](20260928-kcp-scenario-optimization.md)。下列旧默认值与测试数量仅对应首轮证据。

## 必须调整的入口

- 删除 `ServerFactory.kcpUnsupported`、`UnsupportedKcpServer` 和 `NetErrorCode.KCP_NOT_IMPLEMENTED`；它们从未提供可用传输，不保留兼容占位。
- 需要 KCP 的应用显式依赖 `group.zn.zero:zero-net-kcp`，使用 `KcpServer` 和 `KcpOptions`。
- 构造器必须传入 codec、handler、listener、非内联业务执行器以及可选共享 NettyIoResources；KCP 不自动启动或进入默认 starter。
- `ServerOptions.kcp` 保留。KCP 帧上限额外受 `(mtu-24) * min(window,255)` 限制，默认 150528 字节，不沿用 TCP 的 16MiB 能力声明。
- 纯 KCP IO 资源不创建 boss；停止后的 KcpServer 是单次使用实例，重新部署请创建新实例。

## 登录、客户端与回退

生产 TCP 必须通过身份认证并由 framework 填入 SecurityContext/subject/state；默认还要求 TLS_ESTABLISHED。
调用 `issueTicket(control)`，将返回的 conv、32 字节 key、expiresAt 通过已认证 TLS 控制通道下发。
已有裸 KCP 客户端须添加 [ZKCP v1 认证外层](../reference/kcp-transport-contract.zh-CN.md)，并配置相同 MTU/窗口；
客户端不应把票据密钥输出到日志。HMAC 不加密业务数据。

KCP 地址变化、TCP 关闭/重新认证、票据过期或资源耗尽后需重新获取票据。
回退由原控制连接调用 `fallbackToTcp(control, conv)`；等待完成后确认切换，之后只提交新的 TCP 请求。
框架不自动重发未确认请求，应用须明确跨传输的业务幂等与顺序语义。

## 验证证据

2026-09-28 实际执行结果：

```powershell
.\mvnw.cmd -pl zero-net-kcp,zero-runtime-net -am -Pquality verify
java scripts/ZeroArchitectureGuard.java
java scripts/ZeroFrameworkBoundaryGuard.java
.\mvnw.cmd -pl zero-net-kcp -am dependency:tree '-Dincludes=com.github.l42111996:*,io.netty:*,org.slf4j:*,org.jctools:*'
```

- 首轮包含 runtime-net 的 16 个 reactor 项目成功，299 项测试，0 失败、0 错误、0 跳过。随后增加两项资源回归，重跑 `mvnw.cmd -pl zero-net-kcp -am -Pquality verify` 通过，KCP 专项最终为 19 项；两轮合计覆盖 301 项不同测试。
- 受影响模块及本次 reactor 的 Checkstyle、PMD、SpotBugs 全部通过，JaCoCo 报告生成成功。KCP 行覆盖 464/524（88.5%）。
- 架构守卫：57 个模块，0 违规、0 警告；新增 KCP 依赖隔离规则。框架/模板边界守卫通过。
- 依赖树确认 Adapter 引入 kcp-base/kcp-fec 1.6.2；Netty 均为 4.2.17.Final，无上游 SLF4J binding 或 JCTools。
- 测试包括真实 TLS 身份校验/票据下发、KCP 分片收发、双向丢包/重传、乱序/重复、MAC/方向/重放、地址固定、
  非法 segment 隔离、鉴权/TLS/容量拒绝、拥有权校验后的 TCP 回退、空闲/绝对过期/无 ACK 超时、
  有界入出站/生命周期预算、部分 ACK 共享缓冲预算、异步 handler 完成后关闭、直接执行器及 CallerRuns 拒绝、中断启动和借用 IO 回收。

原 `UnsupportedKcpServer` 占位断言已删除；替代证据是新 Adapter 的真实 socket 测试。未运行与本次变更无关的全仓外部中间件集成测试。
验证环境为 Windows、JDK 21、Maven Wrapper 3.9.8、Netty 4.2.17.Final、java-Kcp 1.6.2。
无外部数据库依赖。本次本地证据不代表公网容量、Linux epoll、多语言客户端或长稳验证。
