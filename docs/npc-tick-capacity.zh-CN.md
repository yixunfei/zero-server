# NPC tick 示例容量口径

状态：local example evidence，不是生产容量承诺。

## 范围

`examples/npc-tick` 是独立 Maven 示例，使用正式 `zero-npc` 模块的本地 `LocalNpcZone` API。该示例不连接外部服务，不声明生产模块能力。

示例固定使用：

| 项目 | 值 | 含义 |
| --- | ---: | --- |
| maxNpcsPerZone=4|maxNpcStepsPerTick=2 |
| `maxNpcStepsPerTick` | 2 | 每次示例 tick 最多处理的 NPC step 数 |
| tick 次数 | 2 | smoke run 的固定推进次数 |
| 输出模式 | `local` | 单 JVM、本地内存 |

超过 NPC 数量上限或重复 ID 会显式拒绝；超过每次 tick 的 step 上限的 NPC 会留待后续 tick，不会通过创建线程来扩展。示例 API 要求调用方在同一 owner lane 串行调用，API 自身不创建线程池。

## 可复现验证

```powershell
mvn -B -ntp -f examples/npc-tick/pom.xml clean test
mvn -B -ntp -f examples/npc-tick/pom.xml exec:java
```

期望 marker：

```text
npc-tick=ok|mode=local|ticks=2|steps=2|behavior=patrol|position=12,20|productionReady=false
```

focused smoke tests 覆盖稳定 marker、step budget、容量拒绝和不支持的行为拒绝。

## 不证明什么

这些固定值和运行结果不证明生产 NPC 数量、tick rate、CPU 或内存预算、延迟、吞吐、队列背压、降级策略、跨 Actor 安全、寻路/战斗 AI、持久化、热更、跨服能力或 SLA。只有在正式 API、调度语义、观测口径和性能证据经过单独评审后，才能讨论生产适配；本示例始终输出 `productionReady=false`。
