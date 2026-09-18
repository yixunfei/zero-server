# BUG_ANALYSIS_REPORT 逐项核实与修复记录

- 日期：2026-09-17；基线为本次开始时的未提交工作区，不是 HEAD。
- 用户指定的 `BUG/_ANALYSIS/_REPORT.md` 不存在；经用户确认使用根目录 `BUG_ANALYSIS_REPORT.md`，原报告未改写。
- 已确认执行高风险最小修复方案；事件失败策略另经用户明确选择“继续其他监听器，最终汇总失败”。
- 原报告 14 项：**12 项确认存在，1 项部分成立（#2），1 项误报（#10）**。S1/S2/S4 已确认并修复，S3 在当前实现下不成立。
- “确认存在”指有缺陷的具体路径，不表示报告的全部根因描述、严重度或影响推断准确。下面列明修正。

## 1. 逐项核实

表中源码可在仓库中按类名查找；原样文件在备份目录保留。回归类位于对应模块 `src/test/java`，全部使用 Java 21。

| 条目 | 当前基线结论与判断依据 | 修复与验证 |
|---|---|---|
| #1 内存缓存加载残留 | **存在**。`InMemoryCacheService.getOrLoad` 在 CHM `computeIfAbsent` 回调中同步 `remove`，同步完成时插入/移除顺序错误。实际 JDK 21 还会抛 `IllegalStateException: Recursive update`，并非报告所述只有“静默找不到”。成功 future 已完成后清理异常留在被忽略的派生 stage，导致残留旧 future；同步异常路径还会泄漏原生异常。修复前回归复现失效后仍返回 v1。 | 改为先 `putIfAbsent`，后调用 loader；完成前按 future 身份移除，失败允许重试。`CacheReportAuditTest` 验证同步成功、同步失败及显式写入交错。 |
| #2 事件短路、重试、死信 | **部分成立**。`thenCompose` 链确实在失败后停止，其 publish future 明确失败，并非“静默”。原契约未规定失败后必须继续，故不能仅凭短路判错；用户已明确选择继续派发。死信列表确实无界。`docs/event-model.zh-CN.md` 明确业务异常默认不重试，因此 `retryCount=0` 本身不是 bug；拦截器原本已实现。 | 每个失败处理器记死信，继续按序等待其余处理器，最后首个错误携带 suppressed 汇总。默认死信容量 1024，可配置并查询淘汰数。`EventReportAuditTest` 覆盖同步/异步失败和容量；保持默认不自动重试。 |
| #3 停机未持久化 | **存在**。`DefaultPersistenceManager.doStop` 仅取消定时句柄，不 flush 也不拒绝停机成功。应修正报告表述：内存中的 `dirtyEntries` 并未被 clear，而是进程终止后未保存的数据丢失；没有停机保障。 | 已启动管理器停止时关闭脏入口、等待在途批次并保存所有剩余条目，默认等待预算 30 秒，可配置。失败/超时返回 `PERSISTENCE_FLUSH_FAILED`，生命周期 FAILED，保留脏对象。`PersistenceReportAuditTest` 覆盖 1030 个对象、失败保留、异步超时、并发 flush 合并。 |
| #4 L2 旧值覆盖 L1 | **存在**。`storeL1FromL2` 原先无条件 `putVersioned`；普通异步写响应也可倒序覆盖。负缓存回填还丢失原版本/到期时间，正常回填重置 TTL。 | L1 在 `entries.compute` 内原子选择版本，返回实际保留的新值，保留 L2 到期时间；读取到的版本推进生成器。普通写入完成使用同一合并入口。`LayeredReportAuditTest` 用受控 Future 验证迟到回填，以及读到 v100 后写入 v101。 |
| #5 拒绝输入进入去重表 | **存在**。`seenSequences.add` 在迟到/容量校验之前，第二次提交变为成功而从未缓存。 | 校验和缓存成功后才登记序号；`FrameReportAuditTest.rejectedInputIsNotMarkedAsAccepted` 修复前失败、修复后通过。报告所称“永久 desync”取决于业务，不是该单元路径必然结果。 |
| #6 帧缓冲虚增 | **存在**。同 uid/targetFrame 替换只保留一个输入却每次 `buffered++`，满容量时甚至不允许替换。 | 区分替换与新增，替换不增加占用、也不因总容量满而拒绝。回归以容量 1 连续替换 10 次，再消费和接受下一帧。 |
| #7 房间成员/房间不能回收 | **存在**。LEFT 仍在成员表和快照中，close 仅变状态，原先无释放接口。报告“房间不可销毁”需要区分已有 close 与实际移除。 | leave 移除成员；close 保留最终快照；新增 destroy，仅释放已关闭房间。房间历史事件最多 1024 条并提供淘汰数，避免事件列表继续保留离开玩家。`RoomReportAuditTest` 覆盖轮流进出、开局、历史有界和显式释放。 |
| #8 状态基线回退 | **存在**。SNAPSHOT 无版本/序号判断；DELTA 也可能回退；仅以 observerId 为键导致不同场景相互污染。报告的“永久 RESYNC”过强：新的正确快照原本就能恢复。 | 按 sceneId+observerId 保存版本及 syncSeq；旧版本/重复序号返回 `IGNORED` 并保持基线，错误 DELTA 返回 RESYNC_REQUIRED。`StateReportAuditTest` 验证回退拒绝、后续正常 DELTA 和跨场景隔离。 |
| #9 排行榜 ADD 溢出 | **存在**。`Math.addExact` 直接抛 ArithmeticException，没有绑定业务错误码。 | 映射 `RANKING_SCORE_REJECTED`，在任何榜单写入前拒绝。`RankingReportAuditTest.overflowIsBusinessFailureWithoutMutation` 同时验证原分数保留。 |
| #10 Top 与快照长度不同 | **误报**。`RankingService.queryTop` 只有 limit，没有 offset/cursor，契约写的是 Top-N；完整 snapshot 与个人排名本来就可超出 100 名。报告把它称为分页接口没有依据。 | 不新增分页、不裁切完整快照。150 人回归验证 Top=100、完整冻结快照=150、个人名次=150。另外发现的冻结快照状态校验缺失单独修复，见 N12。 |
| #11 AOI 冗余 UPDATE/LEAVE 缺标识 | **存在**。每次 observe 对 prior∩next 无条件 UPDATE；VisibilityEvent 也没有独立 entityId 字段，因此 entity=null 的 LEAVE 确实无法定位对象。 | 保存观察者最后可见实体，变化时才 UPDATE，LEAVE 带最后可见实体。`AoiReportAuditTest` 验证静止不发、删除后身份仍在；修正一条原本断言冗余 UPDATE 的旧测试。 |
| #12 协议按非法长度分配 | **存在**。数组/集合/bitmap 分配发生在剩余长度检查之前；修复前 5 字节计数输入触发 `Requested array size exceeds VM limit`，Surefire fork 报错。 | 分配前按最小线长度检查；bitmap 先用无溢出的公式检查字节数；nullable 容量拒绝符号范围溢出。`ReaderReportAuditTest` 和原有 round-trip 测试。远程风险取决于实际绑定的 payload decoder；5 字节是恶意字段载荷，不是完整网络 frame。 |
| #13 Java 生成路径越界 | **存在**。包覆盖仅验证非空，renderer 直接 resolve，Windows 反斜杠/绝对路径可脱离根目录。不是简单“任意两个点必定越界”，而是包名被当作路径使用。 | 请求阶段校验 Java 21 包标识符及 DTO 文件名后缀，解析目录后 normalize+startsWith 防线。`CodegenReportAuditTest` 拒绝 slash/backslash/盘符/关键字/非法后缀，既有合法生成测试通过。不声称能抵御攻击者同时替换输出目录符号链接。 |
| #14 指标样本无界 | **存在资源风险**。每次 record 永久追加，导出线性增长。文档明确这是本地原型/测试的历史记录，因此不应把它描述为生产聚合器本身失效。 | 默认最近 4096 条，可配置，`droppedSamples` 显式报告淘汰，保留记录顺序。`MetricReportAuditTest` 验证容量、顺序、计数和导出。 |

## 2. 四项疑点

| 条目 | 判断与证据 | 处理/验证 |
|---|---|---|
| S1 时间轮错过目标桶 | **存在**。锁外读取 tick 后若被挂起，目标桶已被扫过再插入确实可能多等一轮。概率压力测试不能排除该交错。 | 获得桶锁后重验 tick，不一致重新选桶。`KafkaReportAuditTest.schedulingRechecksTickAfterAcquiringBucket` 通过持有目标桶锁、等待调度线程 BLOCKED、推进逻辑 tick，确定性检查任务进入新桶而不是已扫描桶。 |
| S2 超时预算重复起算 | **存在**。invokeRemote 已消耗编码/同步传输时间，waitResult 再给完整 timeoutMillis；无需真实 Kafka 即可构造。两倍不是严格上限，任意阻塞的 SPI 甚至可更长。 | 入口计算一次 deadline 与单调时钟预算，waitResult 仅等待余量。`RpcDeadlineReportAuditTest` 使用延迟传输和可观察的 future 确认预算耗尽后不重新等待。不能抢占同步阻塞的第三方实现。 |
| S3 close/register 漏 future | **当前不成立**。register 在 putIfAbsent 后再次读 closed。插入发生在关闭之前，关闭遍历可见；插入发生在关闭之后，二次检查负责 remove/finish/失败。若另一完成者已经移除，该完成者负责释放。不能仅因 CHM 弱一致迭代就推导永不完成。 | 保留实现；`KafkaReportAuditTest.registrationRacingCloseAlwaysCompletes` 重复 50 次登记/关闭并发验证 future 完成及 size=0，配合已有 pending 超时/复用测试。有限试验不是所有线程交错的形式化证明。 |
| S4 尾随字节 | **存在严格性缺口**。decode 读完 REQUEST/RESPONSE 后直接返回，不检验剩余输入；尾随字节来源可以直接由调用者提供，不需要真实中间件。 | 单 envelope 必须完整消费输入，REQUEST/RESPONSE 均增加尾随字节回归，拒绝结果绑定 CODEC_FAILED。 |

## 3. 报告“非 bug”结论复核

| 原结论 | 本次判断和验证 |
|---|---|
| presence bitmap 双向一致 | 对合法输入成立，ZeroBufferTest.presenceBitsShouldRoundTrip 及 generated codec 往返通过。与恶意计数先分配的 #12 是两类问题，不能混同。 |
| varint/定长整数/zigzag/nullable/字符串/bytes 往返 | 正常输入往返保持成立，ZeroBufferTest primitive/nullable/collection/direct-buffer 测试通过。但“所有 nullable 边界均正确”不能扩大解释：本次发现 nullable 集合计数溢出被视为 null，已修复。 |
| ExecutorActorScheduler 同 lane 串行 | 当前实现保持异步完成后继续 draining；已有 sameLane/order/async wait 测试通过。 |
| ExecutorActorScheduler 跨 lane 不因等待 stage 阻塞 | 现有 shouldNotBlockActorThreadWhileAsyncHandlerIsPending 通过；未重新宣称原报告 5ms 性能数字。 |
| LocalActorScheduler 并发未观察到丢消息 | 新增 ActorReportAuditTest：30 轮×4 生产者×8 消息，同 lane，检查所有完成信号和处理计数。通过只代表本次有限验证。 |
| 持久化精确移除保留新脏标记 | 成立；remove(key, entry) 保留。新增 aNewDirtyMarkSurvivesAnOlderFlush 用受控捕获验证旧 flush 不删除新登记。 |
| NPC skip 无双计数 | 当前 budget 分支仍按未处理项计数；NpcRuntimeTest.tickBudgetLimitsProcessedNpcCount 通过。未声称重做长稳或时间分支全交错证明。 |
| Prometheus 转义与非有限数正确 | 保持成立；PrometheusExporterTest 的 definition order/escape 与 NaN/Inf 测试通过。 |
| 所有 catch ignored 都是有说明防御兜底 | **不成立的全称断言**。LocalRoomService.emit 原本 `catch(RuntimeException ignored) {}` 会吞业务事件消费者失败且没有说明；现已统一包装并传播，测试 eventConsumerFailureIsReported。其他有明确保留主异常/防递归目的的兜底没有盲目删除。 |

## 4. 报告外已修复问题

| 编号 | 问题、修复依据 | 验证 |
|---|---|---|
| N1 | 本地缓存同步异常路径 CHM Recursive update，以及较早异步加载覆盖显式写入/失效。显式修改撤销旧加载回填资格，清理按 future 身份进行。 | CacheReportAuditTest；修复前日志有 Recursive update 和 old 覆盖 new。 |
| N2 | L2 回填延长 TTL、丢负缓存版本；本地生成器不观察 L2 高版本。合并时保留到期时间并推进版本生成器。 | LayeredReportAuditTest 高版本回填后再写及源码原子合并检查。 |
| N3 | 已提交的当前帧输入仍被接受，BUFFER 迟到输入存入永远不会消费的旧帧。改用 <= 检查，BUFFER 转下一帧。 | FrameReportAuditTest 当前帧拒绝和 BUFFER 实际消费。 |
| N4 | 帧幂等键永久积累，EMPTY 策略消费后仍保留空 pending/lastInputs。改为有限近期窗口并清理 EMPTY 的空槽。 | 帧重复/替换/后续容量测试及容量不变量检查；迁移说明明确不承诺无限历史去重。 |
| N5 | 同一 ActorScheduler 创建第二个 FrameMatchRuntime 会重复注册相同消息类型而失败。新增不捕获对局的共享命令路由器，并以弱键避免延长调度器生命周期。 | matchesCanShareSchedulerWithoutHandlerCollision；关闭一个对局后另一个仍可 tick。 |
| N6 | LEFT 成员使 start 的 all-ready 条件永远不满足；历史事件继续无界保留玩家。移除离开成员并限制事件历史。 | RoomReportAuditTest 开局、600 次进出、淘汰计数、destroy。 |
| N7 | 房间回调异常被吞。统一异常传播，已提交状态不回滚。 | eventConsumerFailureIsReported。 |
| N8 | 状态基线只按 observerId 导致跨 scene 污染。 | StateReportAuditTest 场景 B 不得复用场景 A 的基线。 |
| N9 | AOI int 坐标相减/abs 溢出，使 MIN_VALUE 与 MAX_VALUE 被当作近邻。 | distantCoordinatesDoNotOverflowIntoVisibility 修复前失败；改为 long。 |
| N10 | Java DTO 后缀可把文件名变成路径；与包名入口一起校验。 | unsafeJavaDtoSuffixIsRejected。 |
| N11 | 并发持久化 flush 重复捕获/保存同一入口，同步 capture 异常不能正常计入失败结果。单飞合并并把捕获异常转成失败 stage。 | stalledCaptureTimesOutAndConcurrentFlushIsCoalesced、失败保留测试。 |
| N12 | 排行榜 snapshot 未执行现有契约的 FROZEN 限制。现在非冻结/不存在赛季返回 RANKING_SEASON_STATE_INVALID。 | snapshotRequiresFrozenSeason；冻结快照与 Top/个人查询测试。 |
| N13 | Kafka 允许声明大于实际载荷的长度并先分配；虽然有绝对上限，仍造成不必要分配。现在 readLength 同时检查 available。 | envelope 正常往返与畸形输入测试、分配前代码检查。 |
| N14 | Netty TLS_ESTABLISHED 是 Optional<Boolean>，与 Boolean.TRUE 比较永远不相等，强制 TLS 的合法连接也被拒绝。 | SpotBugs EC_UNRELATED_TYPES；TlsReportAuditTest 检查 false 拒绝、true 保留连接。先追加备份再修改。 |
| N15 | CacheLoadCoordinator 的异步结果为 null 时，回调抛异常但公开 future 永远未完成。改为完成异常，并在通知完成之前释放单飞表和配额。 | coordinatorNullResultFailsAndReleasesPermit 检查异常完成、pending=0、容量 1 可再加载。 |
| N16 | nullable 集合的 unsigned sizePlusOne 溢出为负，被当成 null 而接受畸形输入。 | unsignedOverflowCannotMasqueradeAsNull；有效 nullable 往返测试继续通过。 |

## 5. 备份与迁移

- 所有既有被修改文件均先复制原始字节，再修改。备份包含当时已有的未提交修改；没有用 git checkout/reset 回退用户工作。
- 备份及原始日志仅保存在维护者本地任务档案中，不随公开仓库分发。以下备份描述用于记录当时的操作，不是读者复现的前置条件。
- `backup-manifest.json` 记录原相对路径、字节数、SHA-256；`BACKUPS.md` 提供逐文件可读清单；`backup/` 保留相同路径层级。
- 新增文件没有旧版本可备份；见任务 `CHANGES.md`。原始分析报告也有留存副本。
- 0.x 行为/API 调整详见 [迁移说明](../migrations/20260917-bug-report-verification.md)。没有发布、推送、提交或数据库迁移操作。

## 6. 验证证据和边界

最终成功验证按测试类去重合并共 **673 个测试，0 失败、0 错误、0 跳过**；新增 **16 个回归类、36 个测试方法**。备份 **32 个文件**（31 个修改文件及原报告留存），全部 SHA-256 校验一致。

上述计数属于 2026-09-17 未提交工作区的历史结果，不是当前检出的验证承诺。环境为 Java 21、Maven 3.9.8；维护者本机的原始日志和备份不随仓库分发。公开复现命令（仓库根目录）：

```bash
mvn -B -ntp -T 1C -fae test
mvn -B -ntp -T 1C -fae -Pquality verify
mvn -B -ntp -T 1C -fae -Pquality -pl zero-cache,zero-codegen,zero-data,zero-actor -am verify
```

以本次运行的退出码和各模块 Surefire 报告为准；这些命令不需要原始分析报告或本地备份。

- 原有全量 `mvn -B -T 1C test` 通过。
- 新增首批回归在修复前实际失败；`regression-before.log` 保留包括 OOM/Recursive update 的输出。OOM 用例在独立 Surefire fork 中执行，没有实际申请巨大成功内存。
- 修改后的全量测试、quality profile 及最后受影响模块补充质量验证均执行；复现命令见上方。
- quality profile 包含测试、Checkstyle、PMD、SpotBugs（仓库配置为 High 阈值）和 JaCoCo 报告。JaCoCo 生成报告不等同于达到某个未配置的覆盖率门槛。
- 初次 quality 报出两个本次新增 volatile 增量告警和一个原有 TLS 类型比较错误；均修复并保留失败日志，没有跳过检查。
- 并发用例使用受控 future/桶锁交错；有限重复试验不代替形式化并发证明、压测或长稳。
- 没有真实 Kafka/Nacos/MongoDB/Redis/PostgreSQL 外部联调、GM/热更端到端或全系统生产容量测试；没有据此宣称生产就绪或排除了全仓库全部潜在 bug。
