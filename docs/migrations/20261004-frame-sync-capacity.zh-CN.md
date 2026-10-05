# 2026-10-04 帧同步容量迁移说明

适用于 `0.1.2-SNAPSHOT` 开发阶段。本轮不改变公开 API、线程模型、协议或存储格式。

## 变更与影响

`FrameMatchRuntime` 使用 `MissingInputPolicy.MARK` 时，玩家最后一条待处理输入被帧消费后会释放空玩家槽位。此前空槽位一直留在 `pending` 中，长运行对局累计到 `maxBufferedInputs` 个不同玩家后，新玩家即使没有其他待处理输入也会收到 `participant history capacity exceeded`。`EMPTY` 原有释放行为和 `REPEAT_LAST` 为重复上一输入保留玩家历史的行为不变。`EMPTY`/`MARK` 不再为每帧复制上一输入映射；这只删除未使用的工作，不改变帧批次。

使用 `MARK` 的应用若曾依赖已消费玩家继续占用容量，应改为显式维护业务层参与者资格；容量现在只约束仍需保留的输入/历史。无数据迁移或兼容层要求。

## 验证与回退

容量为 1 的确定性回归在修复前因第二个玩家被拒绝而失败，修复后通过；原有三种缺失策略的有序参考模型回归通过。JDK 21 全仓 `-Pquality,benchmarks,integration-tests verify`、架构守卫和公开 API 门禁通过，详见[复核报告](../reports/concurrency-performance-followup-20261004.zh-CN.md)。

回退代码会恢复 `MARK` 的空槽位滞留；正在运行的对局应完成或重建后切换，不能依赖回退清理既有内存状态。

## 未覆盖风险

本轮未执行跨机容量压测或长稳测试；用户回调耗时、外部执行器和单 owner 契约仍由相应装配负责。
