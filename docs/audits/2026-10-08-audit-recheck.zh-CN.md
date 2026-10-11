# 2026-10-08 审计修复复查报告

复查对象：[原审计报告](2026-10-06-full-code-audit.zh-CN.md)及 B0–B6 修复结果。基线为 `zero/full-code-audit-remediation-20261007` 当前未提交工作树，HEAD `e88ff82`。本次只复查，未修改业务源码、已有测试或发布基线。

## 结论

**不能认定全部修复，也不能认定未引入新 bug。** 本次复现 3 个代码问题：新增 logout API 的返回值竞态、Frame close 清理拒绝遗漏、代码生成名称校验遗漏。另有 2 个工程验收阻塞：架构门禁漏检、Redis 独立消费者装配失败。

Logic 锁环与玩家迟到加载另列为有调用前提的风险，不混入以上 3 个确定缺陷。前者已有迁移文档限制，后者涉及允许独立加载的既有 API 契约，不能直接称作“所有正常调用必现的新 bug”。

Java 21 全仓 `-Pquality,benchmarks verify` 本次通过：**63 reactor、47 个测试模块、328 suite、1273 测试，1272 通过、0 失败、0 错误、1 跳过**。通过的是现有回归集；下列独立反例不在其中。未执行 JMH。

原报告的 153 个编号标题已按前序最终处置逐项整理，见[编号矩阵](2026-10-08-audit-recheck-matrix.zh-CN.md)。标题数不能当作独立 bug 数；前序“修复/更正/保留”也不能合并为“全部修复”。矩阵明确区分历史结论、本轮关联测试、独立反例和未验证项，不声称对全部源文件重新完成逐行审计。

## 确定问题

### R1 · P2 · 新增 logout 返回值随清理调度顺序改变（关联 10-1）

位置：[LocalPlayerService.java:248](../../zero-player/src/main/java/group/zn/zero/player/LocalPlayerService.java#L248)。[PlayerService](../../zero-player/src/main/java/group/zn/zero/player/PlayerService.java) 的契约是返回“是否解除当前绑定”，过期会话返回 false。

实际顺序：

1. session lane 核对 account/uid/sessionId，成功移除旧会话及 UID 绑定。
2. logout 投递 player lane 清理，尚未执行。
3. 同账号重新登录成功，建立新绑定。
4. 旧清理发现 UID 已有新会话，以 `false` 完成原 logout。

这样精确匹配且确实成功的登出，与未成功解绑的过期请求返回相同结果。根据结果执行退出通知、持久化清理或统计的调用方会误判。这是 B3 新增 API 路径的问题，不是原报告的“无法重新登录”仍原样存在。

独立探针 `PlayerLogoutResultProbe.java` 用可控调度器仅延迟 player 清理，输出：

```text
exactSessionRemoved=true, replacement=true, logoutResult=false
AssertionError: logout false despite removing exact current binding
```

建议：会话解绑结果在 session lane 固定；player 清理是否因新会话而跳过应独立处理。增加“解绑后立即重登，清理迟到”的契约测试。

### R2 · P2 · Frame 关闭清理投递失败被忽略（10-30 未完全修复）

位置：[FrameMatchRuntime.java:200](../../zero-frame-sync/src/main/java/group/zn/zero/framesync/FrameMatchRuntime.java#L200)。`close()` 先设置 `closed=true`，随后投递 `clearState`，但不观察调度结果。执行器临时拒绝时，方法正常返回；再次 close 又因 closed 标记直接返回。

使用真实 `ExecutorActorScheduler`，先提交一条输入，仅在 close 时让其执行器抛 `RejectedExecutionException`，恢复执行器后重试 close，输出：

```text
closeReturnedNormally=true, retainedPlayersAfterRetry=1
AssertionError: close silently lost cleanup after transient rejection
```

因此对局对象仍被业务持有时，pending payload/参与者/序号水位可长期保留。对象整体变成不可达后仍可被 GC，不能夸大为无条件 JVM 永久泄漏。

现有 `FrameLifecycleBoundaryTest.queuedCloseReleasesAllRetainedState` 只覆盖调度成功的关闭。建议保留同 lane 清理原则，增加可观察、可重试的清理完成状态，不在拒绝后跨线程直接清空业务容器。

### R3 · P2 · Java `Class` 枚举通过校验却生成坏源码（12-9 未完全修复）

位置：[TargetNames.java:37](../../zero-codegen/src/main/java/group/zn/zero/codegen/generator/TargetNames.java#L37)、[codec.java.ftl:42](../../zero-codegen/src/main/resources/codegen/java/codec.java.ftl#L42)。新增保留符号集合没有覆盖模板依赖的 `java.lang.Class`。

输入为合法词法的最小 DSL：

```text
enum Class {
  UNKNOWN = 0
}
struct Box {
  Class type;
}
client_to_server:
@id(101)
putBox(Box value)
```

本轮构建的 `zero-codegen-0.1.2-SNAPSHOT-all.jar`：validate=0、generate=0；随后 Java 21 javac=1：

```text
BoxDTOCodec.java:45: error: type Class does not take parameters
    public Class<BoxDTO> messageType() {
```

生成器导入业务枚举 Class 后遮蔽了模板中的泛型 Class。建议对生成内部类型使用限定名或完整校验导入/派生符号，并以实际编译验证保留名边界。该项是原名称冲突问题的修复遗漏，未据此声称修复前该 DSL 曾经成功。

### R4 · P2 · 架构门禁仍漏检生产依赖（14-1 / B7 未关闭）

位置：[ZeroArchitectureGuard.java:906](../../scripts/ZeroArchitectureGuard.java#L906)。`parseDependencies` 只读取当前 POM 根节点的直接 dependencies；通用父链、profile 生产依赖和全图环没有完整门禁。

在本次隔离目录中复制当前对应文件，运行当前守卫；不修改工作树 POM：

| 夹具 | 应有结果 | 实际退出码 |
| --- | --- | --- |
| 无违规控制组 | 通过 | 0 |
| zero-core 的 profile 增加 Jedis 生产依赖 | 拒绝 | 0 |
| zero-parent 根 dependencies 增加 Jedis 生产依赖 | 拒绝 | 0 |
| zero-cache 增加 zero-player 依赖，形成既有 player→cache 的反向环 | 拒绝 | 0 |

这说明“守卫通过”不能证明全量依赖方向受保护，**不代表当前真实仓库已存在上述三种违规依赖**。B7 计划尚不是完成证据。

### R5 · P2 · Redis 独立消费者装配仍失败（12-14 整体验收未绿）

位置：[RedisConsumerTest.java:20](../../examples/modular-composition/redis/src/test/java/group/zn/zero/examples/composition/RedisConsumerTest.java#L20)。示例使用默认 direct 执行域，而 [ProductionRedisDataProvider](../../zero-runtime-redis/src/main/java/group/zn/zero/runtime/redis/ProductionRedisDataProvider.java) 明确拒绝 `remoteIoMayInline()`。

使用独立消费者外部依赖与当前 reactor `target/classes` 重放相同装配：默认装配 exit=1，错误为 `ZERO-PRODUCTION-ADAPTER-CLIENT-CREATION-FAILED`；显式使用既有 `ZeroRuntimeExecutors.remoteIo(...)` 后 build/close exit=0。没有启动 Redis，也没有把无法连接 `127.0.0.1:1` 当作该错误根因。

这次是当前类的独立装配探针，不冒充重新运行整个 VerifyAdapterCompositions。应修正示例装配并重跑独立消费者门禁，保留生产执行域检查。

## 有调用前提的风险

### C1 · Logic 新锁顺序可死锁，但迁移文档已限制此用法

`LogicSessionManager.close` 持 manager 锁再取 attributes 锁；`DefaultLogicSessionAttributes.update` 持 attributes 锁执行 updater。updater 若查询 manager，与并发 close 形成反向锁序。

`LogicLockProbe.java` 用闩锁固定调度交错；闩锁只用于复现，业务动作是 `manager.findByConnectionId(...)`。JVM `ThreadMXBean.findDeadlockedThreads` 检出双方等待。将相关 3 个类换成隔离编译的 HEAD 版本后，相同探针 exit=0，无死锁，证明锁依赖由当前改动引入。

不过 [B5 迁移说明](../migrations/20261008-audit-b5.zh-CN.md) 已要求 updater 不跨会话/manager 调用形成锁环。因此该结果记录为**已文档化的受限调用风险**，不计入“遵守全部调用前提仍必现”的新增缺陷。公共接口注释只写短时非阻塞/不可递归修改，接入方容易遗漏迁移文档限制；值得进一步消除锁环或统一接口契约。

### C2 · 登出不能隔离先前发起、之后完成的 loadPlayer

`PlayerLogoutProbe.java` 在登录后挂起 cache get，登出成功并确认在线资料不存在，然后完成旧加载。输出 `afterLateLoad=true, session=false`：旧结果重新进入 players 表。

当前 `PlayerLoadRequest` 只有 UID/trace，没有会话代际，且 API/测试允许未登录时独立 loadPlayer。因此这是接入层必须处理的**异步加载生命周期边界**，不能在未决定独立加载契约前，直接要求所有无会话 load 都失败。本轮不将它与 R1 的确定返回值错误混为一项。

## 仍未提供的能力与保留策略

| 编号 | 当前边界 |
| --- | --- |
| 2-4 | afterPublish 已实现；通用重试、持久幂等未实现 |
| 10-11 | Frame checksum/分叉检测未实现 |
| 10-5 | 支持非零初始 routeEpoch；在线拓扑推进、跨进程 fencing 未实现 |
| 10-13 | Scene 按(sceneId,uid)隔离；全局 UID 排他由业务编排 |
| 10-21 | Logic 有容量及显式 close；没有空闲自动回收 |
| 6-2、9-16、10-3、10-6 | durable/ownership/world/ranking 保留幂等收据并在容量满时拒绝；不是自动淘汰或无限长期可写方案 |
| 6-5 | 缓存写超时限制观察时间，物理同键屏障仍保留；后端永久不完成不等于该键可恢复写入 |
| 10-10 | Frame leave 存在；真实断线接入仍需显式调用并阻断旧连接输入 |

这些项目有的属于前提更正或有意策略，不应都称作 bug；但它们也不能被写成已经新增了对应能力。

## 本次验证与边界

| 验证 | 本次结果 |
| --- | --- |
| Java 21 `mvnw.cmd -B -ntp -Pquality,benchmarks verify` | 2026-10-08 21:07–21:14（Asia/Shanghai），退出 0；63 reactor；1273 测试，1 跳过 |
| Checkstyle / PMD / SpotBugs / JaCoCo | 随全仓 quality 完成；不代表测试覆盖了所有新交错 |
| 跳过项 | `zero-net-kcp-redis` 的 `completesAtomicMigrationWhenRedisIsAvailable`；真实 Redis 环境条件未满足 |
| 统计新鲜度 | 仅本轮日志 Running 且修改时间落在本轮的 XML；另排除 11 份历史/外部报告，不累计历史测试数 |
| 当前架构守卫 | 原仓库 exit=0；3 个违规隔离负例也错误 exit=0 |
| 公共 API 基线脚本 | exit=1；Java 启动 javap 遭 Windows CreateProcess error=5，未完成符号比较 |
| 独立反例 | R1、R2、R3 实际复现；C1 当前/HEAD 对照；C2 迟到加载实际复现 |
| Redis 装配 | 当前 classes 默认失败，显式受管 remoteIo 成功；非真实 Redis 集成证据 |

API 历史 `readPresenceBits()` 删除及迁移已在前序记录中说明；本次脚本中断，不能借历史结论宣称本次只剩这一处 API 差异。未修改基线来制造绿灯。

原报告 §16 的 23 个测试缺口，当前已有 logout、ranking 收据/异常原子性、Frame 补帧/水位、缓存失效/超时、GM 授权/幂等/审计、XFF、ownership checkpoint/history 的回归，但 R1/R2/R3 证明相关测试仍有遗漏。索引异常原子性不等于任意损坏自愈；markDirty 不等于恢复失败隔离，仍须显式 retryFailed。PG 单连接/事务状态的前序外部测试、本轮未重跑。

生成器跨 JVM 确定性、完整脚手架/互操作/非空覆盖独立脚本、跨 OS 产物比较没有在本轮全部重跑；不将 B6 日志算成本轮通过。原 §18 的正确项作为设计与前序证据保留，本轮没有据未改源码或文件名匹配宣称对这 38 组全部重新逐行证明。

真实 Kafka/Nacos/PostgreSQL/Redis、Godot、跨机、Linux/macOS、远程 CI、JMH、容量/长稳/强杀等不在本次执行证据内。因此不作“生产就绪”或“零 bug”结论。

## 证据与建议次序

本机任务归档：`tasks/archive/20261008-audit-recheck/`。其中 `evidence/` 保存全仓日志、新鲜 XML/方法索引、153 编号当前源码摘要、探针源码及输出。该目录受仓库忽略规则影响，不随 Git 自动分发；关键触发条件和结果已写入本报告。

建议先修 R1/R2/R3 并加入上述交错/编译负例，再完成 B7 的依赖与 Redis 门禁，解决 API 检查环境问题；对 C1/C2 及未实现能力明确接入契约。本次没有执行这些修复，也未提交、推送或发布。

## 后续修复追加（2026-10-08）

用户随后授权实施修复。本文以上保留复查时的事实与反例；R1–R5 已修复，对应回归及最终验证见[后续修复结果](2026-10-08-audit-followup-results.zh-CN.md)。API 检查已恢复运行，但历史无参 presence 方法删除仍使兼容基线失败；未将延后能力或条件风险改标为全部完成。
