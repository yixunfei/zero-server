# zero-room 容量边界（本地最小切片）

状态：`implemented-local-minimum-slice`；`productionReady=false`。

本文记录 `zero-room` 当前单 JVM、本地内存实现的默认容量和拒绝语义。数值是可复现的本地验证边界，不是吞吐、延迟、SLA 或生产容量承诺。

## 默认限制

| 维度 | 默认值 | 说明 |
| --- | ---: | --- |
| 单房间成员数 | 由 `create` 的 `capacity` 指定（示例为 2） | 必须为正数；已占用席位达到上限后拒绝新成员 |
| 断线重连窗口 | 由 `create` 的 `reconnectWindowMillis` 指定（示例为 5000 ms） | 窗口内允许原成员重连；窗口外拒绝 |
| 房间总数 | 当前服务实例按已创建房间管理 | 当前 API 不提供跨实例全局配额；不应据此推导集群容量 |
| 事件/快照成员 | 快照为不可变成员列表 | 示例输出 room sequence 和成员快照；当前版本不提供持久事件历史配额 |
| 命令积压 | 由 Actor scheduler 的本地调度能力约束 | 不宣称固定吞吐或无界排队；过载行为属于运行时调度边界 |

## 拒绝策略

- 非法房间配置、重复创建和不存在房间：立即抛出稳定的 `IllegalArgumentException` 或 `IllegalStateException`。
- 房间已满：拒绝新加入，不静默踢出已有成员。
- 非 `WAITING` 状态加入、未满足全员 ready 的 start、非 `RUNNING` 状态 settle：拒绝状态变更。
- 断线重连必须使用原成员且位于时间窗口内；过期请求拒绝。
- settlement 使用幂等 key：同 key 重放返回相同结果；不同 key 的重复结算拒绝。
- 示例中的快照和事件序列只输出本地内存状态；不声称事件已持久化或已广播。

## 负载口径

验证负载是单 JVM、单进程、内存对象、room lane 串行命令：两名成员走一条完整生命周期，并打印每次状态快照。测试关注状态转换、顺序、重连窗口和结算幂等，不是 benchmark，也不代表并发房间数、QPS、P99、网络带宽或稳定运行时长。

不包含：外部数据库/缓存/MQ、网络连接、匹配、观战、跨服路由、持久化恢复、广播背压、故障转移和生产部署。

## 证据 marker

```text
room-game=ok|mode=local|events=created,join,ready,start,disconnect,reconnect,settle,close|productionReady=false
```

该 marker 只证明示例命令成功运行，不把示例或单元测试等同于生产就绪。
