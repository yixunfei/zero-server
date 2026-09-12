# frame-sync 容量边界

本文记录 `examples/frame-sync` 本地 deterministic slice 的边界，不是生产容量承诺。

## 默认边界

示例配置为最多 4 名玩家、未来帧窗口 2、单条 payload 最大 64 UTF-8 bytes。状态仅保存在进程内，match owner 串行修改状态；没有内部线程池、网络发送器、数据库或缓存。

- `frameNo` 从 0 开始，只接受下一个连续帧；重复、回退和跳帧拒绝。
- 同一玩家的 `(inputSeq, targetFrame, payload)` 重放返回 `DUPLICATE`；同 seq 不同内容返回 `REJECTED_CONFLICT`。
- future window 外的输入返回 `REJECTED_TOO_EARLY`。
- 已提交帧的输入按 `LateInputPolicy` 拒绝或标记；不会改变已提交帧。
- missing 输入使用显式 `EMPTY_INPUT` 策略，并在 frame 结果的 `missing` 中列出玩家。
- 输入批次按稳定 `uid`、再按 `inputSeq` 排序，snapshot digest 来自已提交帧序列。

## 复杂度和背压

每次 tick 扫描玩家及其待处理输入，排序当前批次，近似为 `O(P + I log I)`；内存随玩家数、future window 和待处理输入增长。真实实现必须在边界处拒绝 payload、玩家和缓存增长，而不是静默丢弃。示例没有异步广播队列；因此不会以慢 subscriber 阻塞 owner，但也不模拟可靠广播、重试或跨进程背压。

## 生产边界

这个示例只证明本地 deterministic minimum slice。它不证明生产吞吐、SLA、rollback、可靠 UDP/KCP、观战、反作弊、跨服一致性、故障恢复或容量规划。生产部署仍需明确时钟来源、持久化/恢复、网络协议、广播 adapter、限流和压测证据。
