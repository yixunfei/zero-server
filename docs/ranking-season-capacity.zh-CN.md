# 排行榜 / 赛季 local 容量边界

状态：`local minimum slice`，不是生产容量承诺。

示例路径：`examples/ranking-season`。它使用单个同步 owner 和内存状态，目的是展示 forthcoming `zero-ranking` API 的业务顺序与拒绝语义。

## 精确边界

| 项目 | local 示例边界 |
| --- | --- |
| 玩家记录 | 每个赛季由 JVM 内存中的 `Map<uid, score>` 保存；没有持久化上限承诺 |
| Top 查询 | `limit` 必须为 `0..100`；返回稳定的 score 降序、uid 升序结果 |
| 幂等记录 | 评分幂等键和结算记录只在当前进程存活期间有效 |
| 快照 | 仅允许对 `FROZEN` 赛季生成；返回不可变的 entries 副本 |
| 写入 owner | 同一示例 API 的调用在单一同步 owner 内串行执行 |
| 后端 | 无 Redis sorted set、Repository、Cache、跨服聚合或网络端口 |

## 流程与拒绝语义

流程为 `CREATED -> OPEN -> FROZEN -> SETTLED -> ARCHIVED`。只有 `OPEN` 接受积分写入；冻结后写入被拒绝。积分合并模式必须显式指定：`SET` 覆盖、`MAX` 取高、`ADD` 累加。重复幂等键且请求内容相同返回原效果；同键不同内容拒绝。

结算支持 dry-run 与 execute。dry-run 不改变状态；execute 记录本地 settlement，并将赛季置为 `SETTLED`；相同 settlement ID 的重复 execute 返回已记录结果，不重复产生效果。归档只能发生在 `SETTLED`。

## 复杂度与背压

当前内存实现每次 Top / snapshot 需要复制并排序玩家集合，时间复杂度为 `O(P log P)`，空间复杂度为 `O(P)`；评分写入为平均 `O(1)`。这不是 Redis sorted set 或分片实现的性能证据。

示例没有异步队列，因此没有隐藏的无界积压：调用方直接得到成功或异常。未来接入异步 owner 时，必须定义队列上限、拒绝/重试、超时和观测字段；不得把 uid、seasonId 或 rankingId 作为高基数指标标签。

## 不代表什么

本示例不证明生产吞吐、延迟、长稳、热榜抗压、跨服一致性、故障恢复、持久化容量或奖励发放安全性。`productionReady=false` 是稳定门槛标记；生产实现前仍需冻结 Redis/Repository/Cache 边界、容量测试、背压策略和审计/补偿契约。
