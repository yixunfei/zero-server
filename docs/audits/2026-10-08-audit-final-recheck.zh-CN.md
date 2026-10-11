# 2026-10-08 审计修复再次复核报告

后续记录：2026-10-09 用户授权修复本报告 F1/F2，处置与新鲜验证见 [F1/F2 修复结果](2026-10-09-audit-f1-f2-results.zh-CN.md)。本页保留原复核时的失败事实与结论。

复核对象：[原审计报告](2026-10-06-full-code-audit.zh-CN.md)、B0–B6 处置及[后续 R1–R5 修复](2026-10-08-audit-followup-results.zh-CN.md)。用户已确认本轮只审查和报告，不修复源码。基线为 `zero/full-code-audit-remediation-20261007` 当前未提交工作树，HEAD `e88ff82`；测试显式使用 JDK 21.0.4。

## 结论：首轮复核不通过

**不能认定原报告问题全部修复，也不能出具“未引入新 bug”的通过结论。** 本轮确认两处修复遗漏：

| 编号 | 优先级 | 关联原问题 | 确定结果 |
| --- | --- | --- | --- |
| F1 | P2 | 10-1 玩家退出回收 | 清理投递被真实 Actor 容量预算拒绝后，会话已解除；预算恢复后重试 logout 无法回收在线资料 |
| F2 | P2 | 12-9 生成名称校验 | 枚举与派生 codec/包名冲突仍通过 validate 和 generate，Java 产物编译失败 |

这两项是**当前可复现的修复遗漏**；本轮未做修复前版本的隔离对照，不把它们武断归类为“此次修改新引入”。已知 C1/C2 条件风险仍可复现；延后能力仍未实现。现有测试全绿不覆盖下述反例。

按用户确认的顺序，**首轮没有通过，因此未进入第二轮全仓独立 bug 扫描**。以下探针和源码检查均属于首轮修复复核及回归边界检查。

## F1 · 玩家解绑后，清理被拒无法通过重试恢复

位置：[LocalPlayerService.java:202](../../zero-player/src/main/java/group/zn/zero/player/LocalPlayerService.java#L202)，重点为 209–220 行。

`LogoutCommand` 先在 `sessionLock` 内删除 `sessions` 和 `uidSessions`，再投递 `RemovePlayerIfOfflineCommand`。第二次投递失败时，`linkDispatch` 仅使 logout future 异常完成，没有保留可重试的清理意图。再次用同一个精确 sessionId 调用 logout，会在 214–216 行直接返回 false，永远不会到达 player lane 清理。

本轮独立探针使用真实 `LocalActorScheduler(new ActorSchedulerConfig(2, 2, 64))`，没有替换、反射或篡改业务实现：

1. 登录并成功加载 UID 7 的在线资料。
2. 在无关 lane 投递一个异步挂起任务，占用一个全局许可。
3. logout 的 session lane 命令占用剩余许可；它先解绑，嵌套投递 player 清理时触发 `ZERO-ACTOR-CAPACITY-EXCEEDED`。
4. 释放无关任务，等待调度预算完全恢复，然后重试原 logout。
5. 通过公开查询 API 核实：会话不存在，但在线资料仍存在。

实测结果：

```text
cleanupFailure=ZeroException{category=SYSTEM, code='ZERO-ACTOR-CAPACITY-EXCEEDED', message='actor capacity exceeded'}
retryLogout=false, sessionPresent=false, playerRetained=true
AssertionError: cleanup lost after scheduler budget recovered
```

小预算用于确定性构造交错；默认预算在负载达到上限时具有同一拒绝分支。失败本身有通知，问题不是“异常被吞掉”，而是**解除绑定与清理意图没有共同的可恢复状态**。长存服务中，没有后续重新登录/正常退出的玩家资料可能长期滞留；不能夸大为对象不可达后仍无法 GC。

这也不等同于历史 C2：本反例没有任何迟到加载，资料在 logout 前已完整加载，失败来自框架自身的正常背压。

建议保留精确会话身份和同 lane 清理，增加有界、可重试的清理状态；不能通过在拒绝回调线程直接清空玩家状态来绕过 Actor 归属。回归应覆盖预算拒绝→预算恢复→重试清理，以及期间有替换登录时不误删新资料。

证据：任务归档 `evidence/PlayerCleanupBudgetProbe.java`、`player-cleanup-budget.log`、退出码 1。另有只拒绝清理 hop 的 SPI 探针得到相同结果，保存于 `PlayerCleanupRejectionProbe.java` / `player-cleanup-rejection.log`。

## F2 · 名称校验仍遗漏导入和类型/包名冲突

位置：[TargetNames.java:102](../../zero-codegen/src/main/java/group/zn/zero/codegen/generator/TargetNames.java#L102)、[JavaCodegenRenderer.java](../../zero-codegen/src/main/java/group/zn/zero/codegen/generator/java/JavaCodegenRenderer.java)。

`javaSymbols` 按声明包分别登记类型，只检查同包的声明冲突；没有检查生成单元实际导入的名称与本类名称冲突，也没有检查某个类型与另一个生成物的包路径冲突。

最小 `.si` 反例一：

```text
enum BoxDTOCodec {
  UNKNOWN = 0
}
struct Box {
  BoxDTOCodec kind;
}
client_to_server:
@id(101)
putBox(Box value)
```

默认布局把枚举放在 `group.zn.zero.generated.dto.BoxDTOCodec`，同时生成 `group.zn.zero.generated.dto.codec.BoxDTOCodec`。后者又导入前者，导致同一个编译单元中类名冲突。将枚举名称及字段类型改为 `codec`，则产生 `dto.codec` 类型与 `dto.codec` 子包冲突。

使用本轮全仓构建生成的 `zero-codegen-0.1.2-SNAPSHOT-all.jar`，调用真实 CLI，再使用 JDK 21 `JavaCompiler` 编译全部生成的 `.java`：

| 输入 | validate | generate | javac |
| --- | --- | --- | --- |
| 原 R3 的 `Class` 枚举控制组 | 0 | 0 | 0 |
| `BoxDTOCodec` 枚举反例 | 0 | 0 | 1 |
| `codec` 枚举反例 | 0 | 0 | 1 |

关键诊断：

```text
error: BoxDTOCodec is already defined in this compilation unit
error: enum classes may not be instantiated
error: enum codec clashes with package of same name
error: package group.zn.zero.generated.dto.codec clashes with class of same name
```

因此原 R3 的特定 `Class` 缺陷已修复，但不能把原 12-9 整体判为关闭。建议按实际生成包、派生名称和导入关系校验符号，或对合法跨包同名类型使用全限定引用；类型/包冲突须在写盘前拒绝。避免只不断补一个全局保留字列表。

证据：`evidence/CodegenNameProbe.java`、`codegen-names.log` 及 `codegen-probes/` 下的输入和生成物。探针驱动正常结束；失败的是其中两次 javac，已分别输出 `javac=1`，不混淆驱动退出码。探针初始包访问/类加载配置失败另存，未当作产品缺陷。`wait`、`getClass` 两个怀疑输入实际可编译，已排除。

## R1–R5 的本轮判定

| 前次编号 | 本轮证据 | 判定 |
| --- | --- | --- |
| R1 logout 返回值竞态 | `PlayerLogoutHandoffTest` 本轮通过；源码在清理阶段跳过替换会话时仍完成 true | 原反例已修复；相邻拒绝边界有 F1，10-1 不完全关闭 |
| R2 Frame 清理拒绝遗漏 | `FrameCloseRetryTest` 三项及现有生命周期回归通过；源码保留失败后可重试的 closeAttempt，观察者不能取消真实清理 | 原反例修复通过；异步调用方仍须观察 closeAsync |
| R3 Class 枚举 | 现有名称边界测试及独立 Class 编译控制组均通过 | 特定反例修复通过；12-9 仍有 F2 |
| R4 依赖守卫漏检 | 当前仓架构 22 条规则通过；21 个隔离夹具通过，包含原 profile/parent/cycle 三个负例 | 原漏检路径修复通过；不扩张为完整 Maven effective model/外部传递供应链证明 |
| R5 Redis 消费者装配 | 本轮完整 VerifyAdapterCompositions 通过，Redis 4、discovery 1、production contract 19 项 | 装配修复通过；没有因此宣称已运行真实 Redis 读写集成 |

## 未实现能力、保留策略与条件风险

- **仍未实现**：2-4 的事件通用重试/持久幂等、10-11 的 Frame checksum、10-5 的在线拓扑推进/跨进程 fencing、10-13 的框架级全局 UID 排他、10-21 的 Logic 空闲踢线。部分属于已声明范围或业务编排责任，不能一律称作代码 bug，也不能写成“对应能力已补齐”。
- **容量保留策略**：durable、ownership、world、ranking 终态幂等收据计入有界容量，满额拒绝新请求。未新增自动淘汰；缓存写超时只结束观察，物理同键屏障与预算仍等待后端完成。这些策略已文档化，不把它们误写为无限长期可写或超时自动恢复。
- **C1 仍可复现**：属性 updater 调用 manager 查询，与并发 close 形成锁环，`ThreadMXBean` 检出两线程死锁。本轮复用了已阅读的历史确定性交错探针。B5 迁移文档已禁止 updater 跨会话/manager 形成锁环，继续记为受限调用风险，不混入 F1/F2。
- **C2 仍可复现**：logout 成功后，之前发起的独立 loadPlayer 迟到完成，重新进入 players 表；公开 API 仍允许无会话独立加载。本轮结果为 `afterLateLoad=true, session=false`，继续保留接入契约边界；不能擅自推导为“所有未登录 load 必须拒绝”。
- **API 门禁仍失败**：本轮工具完整比较 5 个模块，只报告历史删除的 `zero-protocol: public boolean[] readPresenceBits();`。未修改基线、恢复旧 API 或改门禁制造通过。

## 本轮验证

| 范围 | 结果 |
| --- | --- |
| `mvnw.cmd -B -ntp -Pquality,benchmarks verify` | 退出 0；63 reactor，47 测试模块，330 suite，1280 测试，1279 通过、0 失败、0 错误、1 跳过；2026-10-08 23:17:38 +08 完成，耗时 5:08 |
| Checkstyle / PMD / SpotBugs / JaCoCo | 随本轮 quality 完成；benchmarks 编译和现有测试完成，未运行 JMH |
| 跳过项 | `zero-net-kcp-redis / completesAtomicMigrationWhenRedisIsAvailable`，真实 Redis 条件未满足 |
| `ZeroArchitectureGuard` / `VerifyDependencyGuard` | 退出 0；22 条规则、21 个隔离夹具通过 |
| `VerifyCodegenModes` | 退出 0；当前构件的 8 个模式/退出码/手改保护用例通过 |
| `VerifyAdapterCompositions` | 退出 0；先从当前 reactor install，再运行三切片；本次 UUID XML 共 24 测试，零失败/错误/跳过，不与全仓重叠结果累加 |
| `ZeroFrameworkBoundaryGuard` / `ZeroFrameworkGapLedger` / `ZeroReleaseHardeningReadiness` | 均退出 0；材料和静态检查不表示延后能力已完成 |
| `VerifyApiCompatibilityConsumer` | 退出 0，消费者编译通过 |
| `VerifyPublicApiCompatibility --check` | 退出 1，完整比较后仅历史无参 presence API 删除 |
| 独立反例 | F1 真实容量拒绝、F2 两种名称冲突均复现；原 Class 控制组通过 |

只统计本次全仓日志实际 `Running` 且 XML 时间落入本次构建窗口的报告；排除 14 份历史/外部 XML。原始 XML 已复制至任务证据目录，后续消费者门禁不会覆盖此统计。最后核对源码、模板及测试摘要，验证快照之后无源码变化；当前生成器 JAR 摘要另存。

本轮没有运行真实 Kafka/Nacos/PostgreSQL/Redis 服务集成、完整 Stage0/脚手架/跨语言互操作、Godot、其他 OS、远程 CI、JMH、容量长稳、多机和强杀测试。前序相关日志仅作历史证据，不冒充本轮通过。

## 编号覆盖与交付

[本轮编号矩阵](2026-10-08-audit-final-recheck-matrix.zh-CN.md) 包含全部 **153 个编号标题**，关联当前源码入口及本次测试；其中 102 个编号关联到新鲜 suite。关联测试不等于对该编号全部语义独立证明；对仅有历史更正/保留/外部证据的项，矩阵明确保留限制。未声称对全部 61 模块重新逐行证明“零 bug”，也未将原报告第 18 章的 38 组正确结论自动当成本轮新证明。

本机完整证据归档：`tasks/archive/20261008-audit-final-recheck/evidence/`，含 153 编号 JSON、源码摘要、全仓日志/原始 XML、实际测试方法索引、门禁退出码、独立探针、生成物与复现输出。`tasks/` 受 Git 忽略规则影响，因此关键反例与数字同时写入本报告。

本轮只新增复核报告、矩阵及任务证据；未修改业务源码、既有测试或 API 基线，未提交、推送或发布。建议先修 F1/F2 并增加对应负向回归，再对延后能力的验收范围作明确决策，之后重新判定是否满足第二轮扫描的前置条件。
