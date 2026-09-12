# AOI / 状态同步容量边界

## 定位

本文件记录 `examples/aoi-state-sync` 本地 fake in-memory API 的容量口径。它是可重复的行为边界，不是生产压测证据、吞吐承诺或 SLA。示例不修改 `zero-aoi` / `zero-state-sync`，不创建线程池，也不连接网络、数据库、缓存或消息系统。

## 固定默认值

| 限制 | 默认值 | 超限行为 |
| --- | ---: | --- |
| 单 scene 实体数 `maxEntities` | 32 | 拒绝新增实体 |
| 单次 visible query `maxVisible` | 8 | 截断为上限，结果仍按插入顺序稳定返回 |
| 单 observer 待处理事件 `maxObserverQueue` | 2 | 返回/记录 backpressure rejection，不静默丢弃后宣称成功 |
| 单 payload `maxPayloadBytes` | 256 bytes | 拒绝实体输入 |

测试可使用更小的限制验证边界。空白 ID、负视距、空值、重复实体和不存在的实体均拒绝。可见性使用 Chebyshev distance `<= viewRange`；当前实现为线性扫描 O(N)，不是 grid、quadtree 或 BVH。

## 工作负载与顺序

示例工作负载为单 JVM、单 scene owner：注册 observer，新增/移动实体，查询可见集，生成 snapshot，并尝试匹配或过期 baseline 的 delta。成功 mutation 递增 `sceneSeq`；每个 observer 的事件递增 `syncSeq`，事件保持出现/消失的因果顺序。snapshot 是当前可见集的完整基线，未知 baseline 返回 `resyncRequired=true`。

有界队列满时增加可观测的 `queueRejected` 计数；没有后台线程或阻塞等待，也没有静默删除权威实体状态。真实生产系统仍需定义优先级、合并 snapshot、传输重试和监控接入。

## 生产边界

该示例证明 API 路径和 focused tests 可在 JDK 21 本地运行，不证明生产容量、带宽、广播 fan-out、持久化、权限过滤、跨服迁移或客户端协议兼容性。marker 中始终保留 `productionReady=false`；任何生产容量结论都必须由目标 workload 的独立 benchmark、资源预算和故障测试给出。
