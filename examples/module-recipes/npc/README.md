# 正式 NPC 生命周期与有界 tick

覆盖模块：zero-npc、zero-runtime-actor、zero-runtime-bootstrap。依赖见 [pom.xml](pom.xml)，入口见 [NpcExample.java](src/main/java/group/zn/zero/examples/recipes/npc/NpcExample.java) 的 `runFlow()`，测试见 [NpcExampleTest.java](src/test/java/group/zn/zero/examples/recipes/npc/NpcExampleTest.java)。

## 运行

使用 JDK 21、Maven 3.9+，在仓库根目录执行。首次或框架源码更新后先运行 `mvn -B -ntp -DskipTests install`。

```powershell
mvn -B -ntp -f examples/module-recipes/npc/pom.xml test exec:java
```

## 接入步骤

1. 组合根创建受管 Actor 调度器，将它交给 `ZoneActor` 和 `LocalNpcZone`。
2. 创建守卫、商人和一个模拟决策故障的 NPC，重复创建守卫获得 `ALREADY_EXISTS`。
3. 第一次 tick 只允许一个决策；第二次允许三个，决策失败通过 `BEHAVIOR_FAILED` 事件观察。
4. 移除守卫后查询为空，并查看创建/移除审计数。

## 预期结果

limited=DEGRADED（处理 1、跳过 2）；complete=COMPLETED（处理 3）；behaviorFailures=1；remaining=2；audits=4。

`main` 打印 `Result` 后退出；回归测试断言关键结果，异常不会被仅打印后忽略。

## 接入边界

这是正式 `zero-npc` API 的应用消费者；早期 [npc-tick](../../npc-tick/README.zh-CN.md) 是独立教学模拟。

本例 `Long.MAX_VALUE` 时间预算用于稳定展示操作数限制；实际游戏应同时设置有限毫秒预算。`processedCount` 包含发生普通决策异常的 NPC，`COMPLETED` 表示预算内遍历结束，不代表所有行为成功。未自动启用周期 tick；周期接入可参考 [受管定时任务](../../managed-scheduler-local/README.md)。

决策、事件、审计和指标回调都在区域 lane 内，必须短时非阻塞，禁止等待同 lane。通知失败不会回滚已提交状态，应重新读快照确认，不能盲目重复创建。示例使用 `NoopNpcMetrics`，事件/审计只保存在本次内存中。

## 扩展位置

替换 `BehaviorAdapter` 实现业务决策，保持输入快照不可变；通过 `NpcEventSink`、`NpcAuditSink` 和 `NpcMetrics` 接入真实观察系统。

完整注释包含参数、结果、状态修改及线程边界；复制时保留组合根的资源关闭逻辑。返回的结果 record 和列表不可变。

返回 [七类示例索引](../README.md) 或 [全部示例](../../README.md)。
