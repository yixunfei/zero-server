# npc-tick 本地示例

这是一个独立 Maven 示例，使用仓库当前可用的 JDK 21 与本地内存 API 演示 NPC 生命周期片段、行为切换和有界 zone tick。当前仓库没有 `zero-npc` 或 `zero-tick` 公共模块，因此示例不会伪造或依赖尚未发布的 API；未来模块可用时再适配。

在仓库根目录运行：

```powershell
mvn -B -ntp -f examples/npc-tick/pom.xml clean test
mvn -B -ntp -f examples/npc-tick/pom.xml exec:java
```

运行会输出稳定 marker：

```text
npc-tick=ok|mode=local|ticks=2|steps=2|behavior=patrol|position=12,20|productionReady=false
```

示例只验证单 JVM、调用方串行 owner lane 内的内存状态变更。它不创建线程池，不连接网络、数据库或消息系统，也不实现行为树、寻路、战斗 AI、异步 adapter、背压调度器、持久化、热更或跨服能力。`productionReady=false` 是明确的边界标记，不是生产容量、延迟、SLA 或可靠性声明。容量和测试口径见 `docs/npc-tick-capacity.zh-CN.md`；候选正式契约见 `docs/npc-tick-minimum-contract.zh-CN.md`。
