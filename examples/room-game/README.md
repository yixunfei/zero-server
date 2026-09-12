# room-game：zero-room 本地示例

这是一个独立 Maven 示例，直接使用 `zero-room` 展示单 JVM、本地内存房间的完整最小流程：创建、加入、ready、start、disconnect、reconnect、settle、close，以及快照/事件输出。

## 运行

在仓库根目录执行：

```powershell
mvn -B -ntp -f examples/room-game/pom.xml clean test
mvn -B -ntp -f examples/room-game/pom.xml exec:java
```

示例会输出 `room-event=...` 快照行和稳定摘要 marker：

```text
room-game=ok|mode=local|events=created,join,ready,start,disconnect,reconnect,settle,close|productionReady=false
```

## 边界

示例只验证本地 room owner lane 和内存状态，不创建线程池、端口或外部连接；不提供匹配、观战、广播、持久化、跨服迁移或生产容量保证。容量口径、拒绝策略和限制值见 `docs/room-component-capacity.zh-CN.md`。
