# 2026-10-06 用户流程可靠性修复

本次修复面向使用者、接入开发者和运维人员，属于 `0.x` 行为调整。升级前应停止旧的本地受管进程，并确认旧入口占用的端口已释放。

## 行为变化

- `local + net` 生成的 Server 使用显式的 `ServerFactory.tcpUnmanaged` 本地原型入口，只接受字面量 `127.0.0.1` 或 `::1`，不会放宽生产工厂的握手策略。
- demo 和 TCP 入口共用 `LocalGameFlow` / `LocalGameBO`。业务完成前不会记录 SUCCESS 或发送成功回包；失败保留 trace 和稳定 `ErrorCode`，TCP 失败帧设置既有失败标记。
- GM REST 的 `dryRun` 必须是 JSON boolean，字段、重复键、UTF-8、大小和尾随内容严格校验。响应保留当前请求追踪字段，业务结果按错误分类映射状态码；自由消息和结果数据默认隐藏。
- `run` / `demo` 前台执行一次示例。`serve` 在真实 Java JVM 启动后等待带令牌的 ready 文件；`status` 校验 PID 和启动时间；`stop` 请求优雅关闭，超时才强制终止。
- Prometheus `/health` 继续表示端点存活；新增 `/ready` 表示应用可以接收流量。未注入就绪探针的旧构造器返回 503，应用需要接入非阻塞状态快照。

## 升级步骤

1. 使用旧脚本停止已有实例，检查 `server.pid` / `server.meta` 对应进程已经退出。
2. 在新目录生成 local + net 项目并运行 clean test，按 ownership 升级流程迁移已有手写业务；不要直接覆盖已修改的生成工程。
3. 将长驻入口改为 `serve --projectDir ABSOLUTE_DIR`，用 `status` 读取 `zero-status=ready` 后再接入测试客户端；`stop` 返回 `forced=false` 表示未强制终止；仍需结合服务日志判断资源清理和业务排空是否成功。
4. 将编排 liveness 指向 `/health`，readiness 指向应用显式接线的 `/ready`，不要在 HTTP 探针中同步连接数据库或消息系统。
5. 调用 GM REST 时为每个请求发送 `dryRun: true|false`，并审查允许投影到 `result.data` 的字段是否已经脱敏。

`ZERO_STATE_DIR` 可为每个项目设置独立绝对目录；`ZERO_NET_PORT=0` 可用于本地临时端口。进程子命令的相对项目路径按仓库根目录解释。

## 兼容与边界

Room 的内部 `CommandPayload` 已在本轮重构为携带命令数据的私有路由类型，不是公开扩展 SPI；共享 scheduler 的多实例问题随后由 `RoomDispatchRegistry` 明确实例归属解决。后续协议 ERROR 位及清理行为以 [2026-10-07 迁移说明](20261007-full-code-audit-remediation.zh-CN.md) 为准。

这是 `0.x` 开发预览，旧的 `server.pid` / `server.meta` 不会被新入口读取。GM REST 的错误状态和严格输入可能使原先接受字符串布尔值的调用失败。框架不提供生产身份认证、TLS、容量证明、持久审计或自动依赖探测；这些仍由应用显式装配。

## 验证证据

- 受影响模块测试：`zero-gm-rest` 7 项、`zero-monitor` 34 项、`zero-codegen` 88 项，均通过；Checkstyle 通过。
- 生成的 `local` 项目测试和 demo：5 项通过，输出 `logs=7|metrics=6`。
- 生成的 `local + net` 项目：8 项通过，覆盖真实 TCP 成功/业务失败帧、loopback 绑定、占用端口和资源释放。
- Windows 统一入口完整 smoke：生成、诊断、前台运行、真实就绪、status、优雅 stop、空格路径、PID 身份拒绝、错误主类和 readiness 超时均通过；Windows Git Bash 入口同样完成 serve/status/stop；未在原生 Linux/macOS 执行。
- 未覆盖：真实 Kafka、数据库、Nacos、TLS、网关、生产容量和跨机故障恢复。

原始证据保存在仓库 target/user-flow-*.log，完整入口验证目录为 target/cross-platform/windows/entry-1389ad93-9699-429d-b669-8fe4bd45ab5a。这些是本机验证产物，不随 Git 提交；可通过 java scripts/ZeroUnifiedEntryVerifier.java --full-smoke 重现。
