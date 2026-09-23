# 20260914 starter/template TCP 生命周期迁移说明

> 本页保留 2026-09-14 的切片与验证记录。后续新增 local Server 模板，但 2026-09-18 新生成 net 工程发现装配 API 编译不匹配；当前状态见[快速上手](../quickstart.zh-CN.md)，不能沿用本页旧结果判断当前工程可运行。

## 1. 版本与责任信息

- 版本：`0.1.0-SNAPSHOT`（0.x 开发预览）
- 责任范围：`zero-server-starter`、`zero-codegen` 脚手架组件选择与本地 TCP 验收
- 状态：`minimum-slice-implemented / productionReady=false`

## 2. 变更摘要

新增 `ZeroServerTcpApplication`，以构造器注入 `GameRuntime`、`IServer` 和只读 probe。生命周期顺序固定为 `runtime.start → server.start`，停止顺序为 `server.stop → runtime.stop`；监听失败会补偿关闭 runtime，停止失败也会继续尝试 runtime 清理。

脚手架新增显式 `net` 组件选择，使用已有 `NETWORK_LIFECYCLE`/`PRODUCTION_NETWORK_LIFECYCLE` capability，并生成 `zero-net` 与 `zero-runtime-net` 依赖及 `127.0.0.1:0` 本地配置。未选择 `net` 时不生成这两个依赖，也不隐式创建 listener。

## 3. 破坏性变更

这是 0.x 新增 API 和生成配置语义，不修改既有 `ZeroServerApplication` 构造器、协议 ID 或网络 wire format。选择 `net` 会使生成工程进入 `external-test` profile，并要求显式配置网络策略；旧模板默认仍不监听端口。

## 4. 迁移前准备

- 备份现有生成工程和 `zero-scaffold.json`。
- 先运行原工程 `mvn -q clean test`。
- 本地测试使用 loopback 和临时端口，不写入真实凭据或固定端口。
- 业务 executor、production authentication executor 和 observer executor 的所有权由应用明确管理。

## 5. 迁移步骤

1. 对已有生成工程运行 `--plan`/`--diff`，确认 ownership 变化。
2. 需要 listener 时显式选择 `net`，装配 `ServerFactory.tcp` 和外部业务 executor。
3. 使用 `ZeroServerTcpApplication.start()` 启动，使用 `probe()` 读取 listener 状态，使用 `stop()` 关闭。
4. 对端口冲突、重复启停和请求超时分别记录稳定错误或业务结果。
5. 不需要网络时保持默认组件选择；不要通过 `runDemo` 推断长驻网络服务。

## 6. 验证

本次验证命令和证据：

```text
mvnw.cmd -pl zero-server-starter -Dtest=ZeroServerTcpApplicationTest -Dsurefire.failIfNoSpecifiedTests=false test
mvnw.cmd -pl zero-net -Dtest=NettyServerImplementationsTest -Dsurefire.failIfNoSpecifiedTests=false test
mvnw.cmd -pl zero-codegen -Dtest=ScaffoldComponentsTest,ProjectScaffoldGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：starter TCP lifecycle 2 tests passed；zero-net 真实 TCP/TLS 5 tests passed；codegen 网络选择和依赖闭包 12 tests passed。证据包位于 `target/acceptance-evidence/`，其中 `matrix.single-process-tcp` 记录本地 starter TCP focused 验证，`productionReady` 保持 `false`。

## 7. 回滚

使用脚手架 `--abort` 或 `--rollback` 恢复生成文件。移除 `net` 组件并重新生成前必须检查 ownership 冲突；运行时停止失败时保留原始异常和 suppressed cleanup 异常。

## 8. 发布后观察

观察 bind 地址、probe 状态、handler 执行线程、端口释放和 executor 终止。当前 Netty EventLoop 仍由 `NettyTcpServer` 自行管理；外部 handler executor 不由 server 自动关闭。真实长稳、容量、认证、TLS/WAF、背压和跨进程 Kafka 不在本地切片覆盖范围内。

## 9. 完成确认

- [x] 显式 start/probe/stop lifecycle API。
- [x] 端口冲突补偿清理和重复停止幂等。
- [x] 真实 loopback TCP library/starter focused evidence。
- [x] `net` 依赖选择与未选择边界测试。
- [x] `productionReady=false`。
- [ ] 生产长稳、容量、安全和分布式链路仍未证明。

<!-- zero-migration-verification-and-rollback=required -->
