# 排行榜 / 赛季本地契约与扩展边界

当前状态：`implemented-local-minimum-slice / productionReady=false`。对应模块：`zero-ranking`，入口：`RankingService`。使用方式见[可运行示例](../../examples/ranking-season/README.md)，整体状态见[能力矩阵](../capability-matrix.zh-CN.md)。

本页先列当前本地限制和验证口径。末尾保留历史设计输入，其中候选类型、状态和跨服行为可能尚未实现，不应直接作为现行 API 使用。生产扩展仍需评审公共 API、Actor 所有权和数据边界；`requiresConfirmation=true`。

<!-- contract-id=ranking-season-minimum-contract -->

## 本地容量与验证

状态：`local minimum slice`，不是生产容量承诺。

示例路径：`examples/ranking-season`。它使用单个同步 owner 和内存状态，目的是展示 forthcoming `zero-ranking` API 的业务顺序与拒绝语义。

### 精确边界

| 项目 | local 示例边界 |
| --- | --- |
| 玩家记录 | 每个赛季由 JVM 内存中的 `Map<uid, score>` 保存；没有持久化上限承诺 |
| Top 查询 | `limit` 必须为 `0..100`；返回稳定的 score 降序、uid 升序结果 |
| 幂等记录 | 评分幂等键和结算记录只在当前进程存活期间有效 |
| 快照 | 仅允许对 `FROZEN` 赛季生成；返回不可变的 entries 副本 |
| 写入 owner | 同一示例 API 的调用在单一同步 owner 内串行执行 |
| 后端 | 无 Redis sorted set、Repository、Cache、跨服聚合或网络端口 |

### 流程与拒绝语义

流程为 `CREATED -> OPEN -> FROZEN -> SETTLED -> ARCHIVED`。只有 `OPEN` 接受积分写入；冻结后写入被拒绝。积分合并模式必须显式指定：`SET` 覆盖、`MAX` 取高、`ADD` 累加。重复幂等键且请求内容相同返回原效果；同键不同内容拒绝。

结算支持 dry-run 与 execute。dry-run 不改变状态；execute 记录本地 settlement，并将赛季置为 `SETTLED`；相同 settlement ID 的重复 execute 返回已记录结果，不重复产生效果。归档只能发生在 `SETTLED`。

### 复杂度与背压

当前内存实现每次 Top / snapshot 需要复制并排序玩家集合，时间复杂度为 `O(P log P)`，空间复杂度为 `O(P)`；评分写入为平均 `O(1)`。这不是 Redis sorted set 或分片实现的性能证据。

示例没有异步队列，因此没有隐藏的无界积压：调用方直接得到成功或异常。未来接入异步 owner 时，必须定义队列上限、拒绝/重试、超时和观测字段；不得把 uid、seasonId 或 rankingId 作为高基数指标标签。

### 不代表什么

本示例不证明生产吞吐、延迟、长稳、热榜抗压、跨服一致性、故障恢复、持久化容量或奖励发放安全性。`productionReady=false` 是稳定门槛标记；生产实现前仍需冻结 Redis/Repository/Cache 边界、容量测试、背压策略和审计/补偿契约。

<details>
<summary>历史设计输入与扩展候选（不代表现行 API）</summary>

以下内容保留最初的设计范围、备选方案和测试建议；其中“缺少”“下一步”等表述对应设计时点。当前已实现范围以上面的本地契约、示例和源码为准。

## 1. 定位

现有 `scripts/NewLocalGame.java --template ranking-season` 已能生成排行榜 / 赛季原型，帮助用户跑通：

- `.si` 协议。
- DTO / codec / BO / dispatcher 生成。
- 本地 Actor lane 串行修改赛季榜单状态。
- 积分提交、Top 查询、玩家排名查询和赛季切换。
- 本地日志和指标输出。

正式框架能力还缺少：

- RankingService / SeasonService 抽象边界。
- 榜单 score、tie-break、排名页和玩家排名语义。
- 赛季生命周期、赛季切换、冻结、结算和归档语义。
- Redis sorted set、Repository、Cache 和本地内存之间的职责划分。
- 跨服榜、热 key、防击穿、防穿透和降级边界。
- 奖励结算、重复执行、漏发补偿和审计日志口径。

本文的价值是把这些缺口整理成“下一步实现前必须确认什么”，而不是直接把 `ranking-season` 模板升级成生产承诺。

## 2. 范围

本草案覆盖：

- 排行榜模型候选。
- 赛季生命周期候选。
- 积分提交、排名查询和榜单快照候选。
- 数据 / 缓存 / Redis adapter 边界候选。
- 结算奖励、幂等和审计候选。
- Actor / 线程归属候选。
- 日志、指标、TraceId 和 ErrorCode 候选。
- focused tests。

本草案不覆盖：

- 不创建 `zero-ranking`。
- 不创建 `zero-season`。
- 不冻结正式 Java API。
- 不冻结协议字段、协议 ID 或 codegen 规则。
- 不实现 Redis sorted set adapter 运行时逻辑。
- 不实现跨服榜、奖励发放、赛季归档或补偿任务。
- 不承诺生产榜单容量、热 key 处理能力、查询延迟或 SLA。
- 不更新 `docs/module-map.md` 声明候选模块已经存在。

## 3. 概念边界

| 概念 | 候选含义 | 不应混淆 |
| --- | --- | --- |
| Ranking | 一个可排序的榜单逻辑单元 | 不等同于所有积分业务 |
| Season | 榜单生命周期和结算周期 | 不等同于活动系统全部能力 |
| Score | 参与排序的主分数 | 不等同于玩家资产 |
| Tie Break | 分数相同后的稳定排序规则 | 不应依赖不可控 Map 顺序 |
| Rank Snapshot | 某个时间点的榜单快照 | 不等同于实时榜单存储 |
| Settlement | 赛季结束后的奖励结算流程 | 不等同于奖励发放已完成 |
| Idempotency Key | 防止重复提交或重复结算的业务键 | 不等同于全局事务 |

首批建议先冻结抽象语义，不急于承诺完整 Redis、跨服榜或奖励系统。

## 4. 候选模块边界

候选模块只作为后续确认问题，不代表本仓库当前已经存在该模块。

| 候选模块 | 候选职责 | 禁止越界 |
| --- | --- | --- |
| `zero-ranking` | 榜单定义、积分提交、排名查询、快照和容量策略候选 | 不直接依赖 Redis adapter；不直接发奖励；不直接写网络 |
| `zero-season` | 赛季生命周期、冻结、切换、结算和归档候选 | 不绕过审计；不直接修改玩家资产 |
| `zero-cache` 协作 | L1/L2 缓存、失效、防击穿和降级候选 | 不把高基数 key 暴露为指标标签 |
| `zero-data` 协作 | 榜单快照、赛季记录和结算记录持久化候选 | 不绕过 Repository / DataService 抽象 |
| `zero-data-redis` 协作 | Redis sorted set adapter 候选 | 只能通过 adapter / SPI 接入正式模块 |
| `zero-gm` 协作 | GM 调分、重算、补发、冻结和 dry-run 候选 | 不允许无审计的高危操作 |

推荐依赖方向仍应保持：

```text
zero-ranking -> zero-core / zero-game / zero-event / zero-cache / zero-data
zero-season -> zero-core / zero-game / zero-event / zero-data / zero-log
zero-ranking -> zero-log / zero-monitor
zero-season -> zero-log / zero-monitor
adapter -> zero-ranking only through SPI or Repository / Cache abstraction
starter -> zero-ranking / zero-season
```

正式创建模块或改变依赖方向前，必须同步更新 `docs/module-map.md`。

## 5. 排行榜模型候选

候选字段：

- `rankingId`
- `seasonId`
- `uid`
- `score`
- `rank`
- `tieBreakValue`
- `updatedAt`
- `version`
- `traceId`
- `idempotencyKey`

候选规则：

- 同一 `rankingId + seasonId + uid` 应只有一个当前分数。
- 分数提交语义必须明确：覆盖、取最大值、累加或业务自定义。
- tie-break 必须稳定且可复现。
- 查询 Top N 必须有最大 `limit`。
- 查询玩家排名应说明不存在时返回空、默认排名还是错误。
- 排名结果集合应说明是否有序、是否可变、是否可能为空、是否线程安全。

## 6. 赛季生命周期候选

候选状态：

| 状态 | 含义 |
| --- | --- |
| `CREATED` | 已创建但未开始 |
| `OPEN` | 可提交分数和查询 |
| `FROZEN` | 停止写入，允许读取和快照 |
| `SETTLING` | 正在结算奖励或生成归档 |
| `SETTLED` | 结算完成 |
| `ARCHIVED` | 已归档，默认只读 |
| `CANCELLED` | 赛季取消或异常终止 |

候选规则：

- 赛季切换必须可审计。
- `OPEN -> FROZEN -> SETTLING -> SETTLED -> ARCHIVED` 是推荐主路径。
- `SETTLING` 不应接受新的积分提交。
- 赛季切换与结算必须具备幂等 key。
- 取消赛季必须说明是否保留快照和是否发放补偿。

## 7. 积分提交候选

候选提交模式：

| 模式 | 语义 | 风险 |
| --- | --- | --- |
| `MAX` | 只保留历史最高分 | 需要比较和并发保护 |
| `SET` | 覆盖当前分数 | 可能误覆盖高分 |
| `ADD` | 增量累加 | 需要幂等防重复 |
| `CUSTOM` | 业务自定义合并 | 需要明确可测试规则 |

候选规则：

- 积分提交必须校验赛季状态。
- 异常分数、非法 uid、缺失 rankingId 应绑定 ErrorCode。
- 重复 `idempotencyKey` 必须可预测处理。
- 高并发提交应避免全局锁。
- 分数更新失败不能只打印日志后继续。
- 提交后是否立即可查必须明确。

## 8. 查询与快照候选

候选查询：

- `queryTop(rankingId, seasonId, limit)`
- `queryAround(rankingId, seasonId, uid, before, after)`
- `queryPlayerRank(rankingId, seasonId, uid)`
- `querySeasonSnapshot(rankingId, seasonId, snapshotId)`

候选规则：

- 查询 limit 必须有上限。
- Top 查询结果必须有序。
- 玩家不存在时返回语义必须明确。
- 快照应包含生成时间、版本和来源。
- 快照生成不能长时间阻塞排名写入路径。
- 归档快照和实时榜单不能混淆。

## 9. 数据、缓存与 Redis 边界

候选职责：

| 层 | 候选职责 |
| --- | --- |
| 本地内存 | local/prototype、小榜单缓存、短期读优化 |
| Redis sorted set | 热榜排序、Top 查询、玩家分数查询 |
| Repository / DataService | 赛季元数据、快照、结算记录、审计关联 |
| Cache 抽象 | 防击穿、防穿透、版本号、失效和降级 |

候选规则：

- `zero-ranking` 不得直接依赖 Redis adapter。
- Redis key 格式、TTL、分片策略和版本号属于高风险数据契约。
- 写入 Redis 和持久化记录之间的失败策略必须明确。
- 落库失败时应保留现场、降级服务，避免无界重试。
- Redis 不可用时，local / prototype 可以降级，本地生产语义必须单独确认。
- 跨服榜应优先通过明确的 adapter / service abstraction 接入。

## 10. 结算奖励与幂等候选

候选字段：

- `settlementId`
- `rankingId`
- `seasonId`
- `snapshotId`
- `rankRange`
- `rewardPlanId`
- `operator`
- `dryRun`
- `idempotencyKey`
- `traceId`

候选规则：

- 结算必须先明确 dry-run / execute。
- 奖励发放必须具备幂等保护。
- 结算失败必须记录失败项、原因和可补偿状态。
- 重复结算同一 `settlementId` 必须可预测。
- 高危 GM 重算、补发、撤销必须记录审计日志。
- 结算不应在 ranking actor 内执行不可控远程 IO。

## 11. Actor 与线程归属

候选归属：

- 同一 `rankingId + seasonId` 的写入可绑定 ranking actor / season actor / sharded lane。
- 查询可走缓存或只读快照，但写入和切换必须保持顺序。
- 赛季切换命令必须串行化。
- 奖励发放、归档和跨服同步应异步化并回写状态。
- 业务代码禁止直接创建线程池。

候选流程：

```text
submit score / season command
  -> validate command and traceId
  -> dispatch message to ranking or season actor
  -> apply score merge or season transition
  -> update cache / repository through abstraction
  -> emit ranking / season event
  -> enqueue metrics / log / settlement side effect
```

需要避免：

- 直接在 IO 线程执行榜单排序或结算。
- ranking actor 等待奖励发放 RPC。
- 使用无界队列积压积分提交。
- Redis key、玩家 uid、rankingId 作为 Prometheus 高基数标签。
- 赛季切换和奖励执行缺少审计。

## 12. 日志、指标与 TraceId

日志候选分类：

- 业务日志：积分提交、排名变化、赛季切换。
- 审计日志：GM 调分、冻结赛季、重算、补发奖励。
- 性能日志：Top 查询耗时、玩家排名查询耗时、快照生成耗时。
- 错误日志：非法分数、赛季状态错误、Redis / Repository 失败。
- 安全日志：异常高分、越权调分、重复结算。

基础字段候选：

- `traceId`
- `rankingId`
- `seasonId`
- `uid`
- `score`
- `rank`
- `seasonState`
- `settlementId`
- `idempotencyKey`
- `errorCode`
- `durationMs`

指标候选：

- `zero_ranking_submit_total`
- `zero_ranking_query_total`
- `zero_ranking_query_duration_seconds`
- `zero_ranking_cache_hit_total`
- `zero_ranking_cache_miss_total`
- `zero_season_transition_total`
- `zero_season_settlement_total`
- `zero_season_settlement_duration_seconds`
- `zero_ranking_backend_error_total`
- `zero_ranking_actor_queue_size`

指标标签原则：

- 标签必须低基数。
- 默认不把 `uid`、`rankingId`、`seasonId`、`settlementId` 作为 Prometheus 标签。
- 可选标签候选：`rankingType`、`seasonState`、`operation`、`result`、`backend`。

## 13. ErrorCode 候选分类

候选分类只用于后续确认，不修改当前 ErrorCode 结构。

- `RANKING_NOT_FOUND`
- `RANKING_SEASON_NOT_FOUND`
- `RANKING_SEASON_STATE_INVALID`
- `RANKING_SCORE_REJECTED`
- `RANKING_SCORE_DUPLICATE`
- `RANKING_QUERY_LIMIT_EXCEEDED`
- `RANKING_BACKEND_UNAVAILABLE`
- `RANKING_SNAPSHOT_FAILED`
- `SEASON_TRANSITION_REJECTED`
- `SEASON_SETTLEMENT_DUPLICATE`
- `SEASON_SETTLEMENT_FAILED`
- `SEASON_REWARD_REJECTED`
- `SEASON_PERMISSION_DENIED`

对外错误必须绑定 ErrorCode；错误日志也必须绑定 ErrorCode。

## 14. Focused Tests

后续正式实现前，建议先确认以下测试口径：

| 测试名 | 验证点 |
| --- | --- |
| `scoreSubmitRejectsClosedSeason` | 关闭或结算中的赛季拒绝提交 |
| `scoreMergeModeIsExplicit` | 分数合并模式必须显式 |
| `rankingTopIsOrderedAndLimited` | Top 查询有序且限制 limit |
| `playerRankReturnsEmptyWhenMissing` | 玩家无榜单数据时语义明确 |
| `tieBreakIsStable` | 同分排序稳定可复现 |
| `seasonTransitionIsIdempotent` | 赛季切换幂等 |
| `settlementDoesNotRunTwice` | 同一结算不重复发奖 |
| `snapshotMatchesFrozenRanking` | 冻结后的快照与榜单一致 |
| `backendFailureDoesNotSwallowException` | Redis / Repository 失败不吞异常 |
| `metricsDoNotUseRankingIdLabels` | 指标标签不包含高基数 rankingId |

## 15. 高风险确认问题

进入实现前必须暂停并确认：

- 是否允许创建 `zero-ranking` 和 `zero-season`？
- 是否允许新增公共 API / SPI？兼容策略是什么？
- 是否需要新增协议字段、协议 ID 或 codegen 规则？
- 榜单 score 合并模式、tie-break 和查询 limit 如何定义？
- Redis key、TTL、版本号、分片策略和降级如何定义？
- 赛季生命周期和状态流转如何定义？
- 结算奖励、dry-run / execute、幂等和补偿如何定义？
- GM 调分、重算、补发和撤销是否进入第一批范围？
- 哪些 ErrorCode、日志字段和指标必须首批冻结？
- 是否需要同步更新 `docs/module-map.md`？

## 16. 推荐推进顺序

```text
ranking-season scaffold
  -> RunLocalScaffold
  -> 阅读生成项目的 BUSINESS_GUIDE.md / NEXT_STEPS.md
  -> docs/reference/ranking-season-minimum-contract.zh-CN.md
  -> 提交 GitHub Design Proposal
  -> 维护者评审积分 / 赛季 / 缓存 / 结算与兼容边界
  -> focused tests
  -> 最小 runtime 实现
  -> docs/module-map.md 同步
  -> quickstart / examples 同步
  -> performance evidence
```

## 17. 不证明什么

本文不证明：

- `zero-ranking` 或 `zero-season` 已经存在。
- 排行榜 / 赛季 API 已冻结。
- Redis sorted set、跨服榜或缓存策略已经实现。
- 赛季结算、奖励发放或补偿已经完成。
- 生产容量、热 key、查询延迟或 SLA 达标。
- 用户已经确认高风险实现。

本文只证明：排行榜 / 赛季正式化前，已经有一份可被 doctor、readiness、advisor、roadmap 和 implementation slice selector 发现的最小契约草案。

</details>


## 2026-09-17 报告核实修订

ADD 溢出返回 RANKING_SCORE_REJECTED 且保持原分数。queryTop 是最多 100 条的 Top-N 查询，snapshot 是完整榜单快照，queryPlayerRank 可以返回 100 名以外的位置；三者不是同一分页接口，不应据结果长度差异判定契约冲突。
