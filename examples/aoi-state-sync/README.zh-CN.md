# AOI / 状态同步本地示例

这是一个独立 Maven 示例，使用 fake local in-memory API 演示单 scene-owner 的 AOI 与状态同步语义。它不依赖网络、数据库、线程池或 `zero-aoi` / `zero-state-sync` 模块。

```powershell
mvn -B -ntp -f examples/aoi-state-sync/pom.xml clean test
mvn -B -ntp -f examples/aoi-state-sync/pom.xml exec:java
```

运行输出包含稳定 marker：

```text
aoi-state-sync=ok|mode=local|sceneSeq=...|syncSeq=...|visible=...|snapshot=...|deltaApplied=true|resyncRequired=true|queueRejected=...|productionReady=false
```

示例路径覆盖：创建 observer/entity、移动、Chebyshev 可见查询、出现/消失事件、snapshot、匹配 delta、baseline mismatch 的 resync-required，以及有界 observer queue。所有 mutation 由调用方按 owner lane 顺序调用；示例 API 自身不创建线程。

固定默认容量为 `maxEntities=32`、`maxVisible=8`、`maxObserverQueue=2`、`maxPayloadBytes=256`。这些值只用于演示拒绝边界，不代表生产吞吐、SLA、广播能力或跨服能力。
