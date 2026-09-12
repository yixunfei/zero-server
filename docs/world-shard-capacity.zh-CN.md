# World / Shard 容量边界

状态：local minimum slice，`productionReady=false`。

默认验证边界：

- 单 JVM、内存状态。
- 两个 shard、单实体迁移流程。
- 每个实体只有一个 owner shard；迁移记录以 `migrationId` 有界保存。
- `routeEpoch` 和 `stateVersion` 使用单调值进行旧路由 fencing。
- 迁移阶段为 source lock → target prepare → target commit → source release；prepare 失败恢复 source owner。

超出范围的实体、迁移队列、持久化、跨进程 handoff、RPC 超时恢复、跨服广播、AOI stitching、再平衡、吞吐、延迟、SLA、长稳和生产容量均未证明。模块不创建线程池或远程 IO；事件和 metrics 仅为通知 SPI，不能替代可靠迁移日志或灾备流程。

验证命令：

```text
mvn -B -ntp -f examples/world-shard/pom.xml clean test
mvn -B -ntp -f examples/world-shard/pom.xml exec:java
```
