# 2026-10-08 复查后续修复结果

依据[复查报告](2026-10-08-audit-recheck.zh-CN.md) R1–R5。用户确认本轮计划后，在 `zero/full-code-audit-remediation-20261007`、HEAD `e88ff82` 的已有未提交成果上实施。未提交、发布、重写 API 基线或恢复已废弃 API。

## 结论

**R1–R5 已修复并通过对应回归；不能据此宣称原审计所有产品缺口消失，或不存在任何新 bug。** 全仓质量门禁通过，API 兼容门禁仍有一项已知历史破坏。

| 项目 | 修复行为 | 验证证据 |
| --- | --- | --- |
| R1 logout 结果竞态 | 精确解绑成功即保持 true；清理与登录绑定检查互斥，替换登录阻止旧清理，回调在锁外完成 | `PlayerLogoutHandoffTest`：延迟 player cleanup → 替换登录/加载 → 执行旧清理；断言成功结果、新会话/数据保留、旧 session 返回 false。修复前失败已保存 |
| R2 Frame 清理拒绝遗漏 | `closeAsync()` 永久停止准入，在同 lane 清理；在途合并、失败可重试、成功幂等；同步 close 未完成明确报错，不阻塞 Actor | `FrameCloseRetryTest` 3 个测试覆盖真实 Executor 拒绝、同步抛出、延迟失败、重试、取消观察副本、共享 scheduler；既有 queued close 全状态释放回归通过 |
| R3 Class 枚举编译失败 | 模板全限定 JDK 类型/注解/异常；真实 runtime 导入及 java 包根冲突提前拒绝 | `CodegenValidationBoundaryTest` 实际生成并用 JavaCompiler 编译 Class/JDK 同名枚举；`TargetNamesBoundaryTest` 拒绝 runtime 导入与 java 根遮蔽。Class 原失败、java 根额外探针均有证据 |
| R4 依赖门禁漏检 | 独立守卫解析父链/profile/BOM，生产环和边界独立于 63 节点/259 声明边清单；架构门禁传播失败，CI 执行负向回归 | `VerifyDependencyGuard` 21 个隔离正负用例通过；profile/parent/生产环三种原漏检均拒绝，硬违规不依赖清单校验 |
| R5 Redis 独立消费者失败 | 显式受管 remoteIo，runtime 正常关闭，外围作用域保护 build 失败 | 完整 `VerifyAdapterCompositions` 通过；Redis 4 测试，discovery 1 测试，production contract 19 测试；独立 classpath 无其他驱动/Starter/Netty |

另修复两个验收脚本问题：API 检查使用当前 JDK 进程内 javap，避免 Windows 启动拒绝；Adapter 门禁保留原始日志，按字节读取 ASCII 标记以兼容 Windows 本地编码。缺口台账的 NPC/World 过时定位标记同步到当前入口，能力状态与原有测试证据保留。

## 本轮验证

| 命令/范围 | 实际结果 |
| --- | --- |
| Java 21 `./mvnw.cmd -B -ntp -Pquality,benchmarks verify` | 63 reactor 成功，47 测试模块，330 suite，1280 测试：1279 通过、0 失败、0 错误、1 跳过；Checkstyle/PMD/SpotBugs/JaCoCo 通过；22:50:18 +08 完成 |
| 最后追加 java 包根名称校验后：`-pl zero-codegen -am -Pquality verify` | 6 reactor 全部成功，22:53:26 +08 完成；不把重复测试叠加进全仓统计 |
| `ZeroArchitectureGuard` / `VerifyDependencyGuard` | 22 条架构规则通过；21 个隔离夹具通过 |
| `VerifyAdapterCompositions` | 当前 reactor install 后，3 个验收切片全部通过；UUID 匹配的新鲜 XML，合计 24 测试，与全仓重叠部分不相加 |
| `VerifyCodegenModes` | 当前生成器构件 8 个独立 JVM 模式/退出码/手改保护用例通过 |
| `ZeroFrameworkBoundaryGuard`、`ZeroFrameworkGapLedger`、`ZeroReleaseHardeningReadiness` | 全部通过；材料检查不代表能力完整或生产就绪 |
| `VerifyApiCompatibilityConsumer` | 编译通过 |
| `VerifyPublicApiCompatibility --check` | 工具完整比较 5 模块；退出 1，仅报告 `zero-protocol: public boolean[] readPresenceBits();` 删除 |

全仓计数来自本次 log 的实际 Running suite 与时间窗口内 XML，14 个旧/外部报告被排除；原始 XML 已另存，不受后续目标构建覆盖。唯一跳过为 `zero-net-kcp-redis / completesAtomicMigrationWhenRedisIsAvailable`，未启动真实 Redis。

证据归档：`tasks/archive/20261008-audit-followup-fixes/evidence/`。包括 `red-player.log`、`red-tests.log`、`full-quality.log`、`fresh-test-index.json`、`full-quality-xml/`、各命令退出码与中间失败日志。隔离依赖夹具及消费者 UUID 报告已复制到归档 evidence 子目录。

## 迁移与剩余边界

Frame 异步 scheduler 调用方须观察 `closeAsync()`，不要在当前 lane join 或在清理前关闭 scheduler。生成器保留名与 Redis 执行域用法见[迁移说明](../migrations/20261008-audit-followup.zh-CN.md)。

API 基线仍红灯：无参 presence 方法因安全预算问题已在前序修复删除，本轮未恢复或通过改基线隐藏。用户应迁移到 schema 数量参数版本；如需调整发布基线，应单独审核。

复查中的 C1 跨会话锁环限制、C2 独立 load 与 session 契约未隐式改变。事件通用重试/持久幂等、Frame checksum、World 在线拓扑/fencing、Scene 全局 UID 排他、Logic 空闲踢线等延后能力仍未完成。未重新声称对原 153 编号完成全源码逐行审计。

未运行真实中间件集成、JMH 长稳、其他 OS 或完整 Stage0/客户端跨语言互操作。本轮已有验证没有发现新增失败；这不构成无缺陷证明。
