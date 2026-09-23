# 2026-09-22 调度、AOI 与编码分配优化

本次为 `0.1.0-SNAPSHOT` 开发阶段优化，无版本号或协议字节格式变更。Java 基线仍为 21，无预览编译/运行参数，也无新的运行时依赖。

## 接入与行为

- `ExecutorActorScheduler` 从实例锁改为 64 个分段短锁；继续使用应用提供的 Executor，保持同 lane FIFO、异步完成边界和拒绝恢复。队列仍无界。处理器精确类型优先，再按注册顺序匹配父类/接口；已排队消息使用提交时的处理器。注销句柄只删除自身注册，重复关闭旧句柄不影响相同 handler 对象的新注册。
- 异步 future 恰好在完成检查和回调注册之间完成时，当前 drain 直接继续，避免 direct executor 递归续调耗尽栈。正常异步续调仍提交外部 Executor。
- `InMemoryAoiIndex()` 默认网格边长为 64，新增 `InMemoryAoiIndex(int cellSize)`，边长必须为正。单位与 `Position` 一致，依据常用视野和密度选择；不改变闭区间 Chebyshev 可见性、增量事件排序和序号。所有读写仍必须由场景所有者串行调用；`visible(null, range)` 即使在空索引上也明确拒绝。负数范围仍拒绝。
- 网格为额外内存索引，跨格移动需要维护桶；极大范围只遍历已占用桶，全体可见时仍需返回全部结果。它不消除广播扇出或观察者快照的成本。
- 自定义 `ZeroPayloadCodec.write` **只能在同步调用内借用 writer、buffer、切片和 ByteBuffer 视图**，不得在返回/异常后继续使用或交给异步任务。需要保留数据时创建副本。常规生成 codec 已符合该规则，无需重新生成协议代码。
- `GeneratedProtocolCodec`、`ZeroBinaryFrameCodec` 每个平台线程最多缓存一个不超过 64 KiB 的临时堆 writer；大缓冲不保留，虚拟线程不缓存，嵌套调用独立借用。`reset` 不擦除字节，业务必须完整写入输出。返回数组仍独立，调用方无需归还；`ZeroBuffers` 的公开工厂不引入池化或引用计数。
- direct 批量复制采用绝对 ByteBuffer 操作。native 原始地址操作补充 `Reference.reachabilityFence`，初始化失败清理已分配内存；调用方仍需独占访问并 `close`，不得并发释放。

## FFM 决策

[JEP 442](https://openjdk.org/jeps/442) 明确 Java 21 的 FFM 是第三次预览，[JEP 454](https://openjdk.org/jeps/454) 在 Java 22 正式定版。本次不将预览 API 引入 Java 21 库。`ZeroUnsafe`、`NativeMemoryZeroBuffer` 已注释该依据及后续迁移要求：Arena 线程约束、关闭规则、扩容后视图与切片存活必须一并设计；性能需要实测，不能推导为所有场景零成本。

## 验证

本轮功能、质量检查及可复现基准入口见[性能优化报告](../reports/performance-feedback-20260922.zh-CN.md)。报告记录本地环境、原实现提交、优化后源码摘要、完整测量样本与未覆盖范围。

## 回退与风险

需要回退时成组恢复本次 Actor、AOI、codec/buffer 源码并重新构建依赖模块。采用了带 cellSize 构造器的业务需先改回无参构造。此次未变更存储格式或 wire format，无数据迁移；缓冲借用约定仍建议遵守。

分段哈希碰撞、单热点 lane、密集 AOI、超大包可能没有吞吐收益。长寿命平台线程的缓存总预算随线程数增长，最多约 64 KiB × 编码线程数加对象开销。原生内存仍使用 Unsafe，栅栏不替代并发所有权约束。线上尾延迟、长稳、native Cleaner 调度和真实地图/协议流量需在部署环境继续验证。
