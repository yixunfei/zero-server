# 2026-10-02 全仓审计修复迁移说明

本次审计在 `zero/audit-c1-c8-fixes` 分支上执行，覆盖已有报告的 H1-M22、低风险项和当前工作区新增回归。已确认有效的问题才进入代码修复；本说明对应 0.x 开发阶段行为变化。

## 已修复

- 缓存 loader 同步抛出 `Error` 时现在也会完成失败清理，释放 singleflight 许可并移除 pending key，后续同 key 加载不会永久阻塞。
- 告警评估器开始执行 `AlertRule.holdFor`，按规则和标签维度去重告警；条件恢复后允许下一轮重新触发。
- Redis cache envelope 解码增加 16 MiB payload 上限和剩余字节检查，损坏长度不会在 `readNBytes` 前触发异常大内存分配。
- `RedisCacheStore` 的同步 Jedis 操作改由注入的执行器承载；生产 Redis provider 使用受管 remote-IO 执行域，避免 Actor/调用线程直接阻塞。
- `OwnershipException` 绑定 `ErrorCode` 并保留 cause；PostgreSQL ownership SQL 失败保留统一系统错误码和根因。
- `InMemoryFencedStateStore` 不再持有本地状态锁执行 ownership store 的远程调用，减少网络抖动对本地读写的全局阻塞。
- 根 POM、模块 parent POM 与 examples 的版本统一到 `0.1.2-SNAPSHOT`，避免干净环境回退解析 `0.1.1` parent。

## 验证边界

- JDK 21 下的全仓 `mvn test`、目标模块回归测试、`-Pquality -DskipTests verify`、架构守卫和框架边界守卫均通过。
- 本次没有运行真实 Kafka、Redis、PostgreSQL 集群的故障注入、跨节点长稳或生产容量验证。
- 事件总线完整重试/幂等策略、生产文件/Kafka 日志 sink、ServerDrainOrchestrator 的进程级 SIGTERM 接线等仍属于后续能力缺口，不在本次审计中伪装成已实现。
