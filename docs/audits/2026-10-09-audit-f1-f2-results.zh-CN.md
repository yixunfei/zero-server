# 2026-10-09 F1/F2 修复与验证

用户已确认修复 [2026-10-08 复核报告](2026-10-08-audit-final-recheck.zh-CN.md) 中的两处遗漏。本轮在原未提交工作树上实施，HEAD `e88ff82`，保留其他审计成果；没有提交、推送、发布或调整 API 基线。

## 修复与独立反例复验

| 项目 | 修改 | 原探针当前结果 |
| --- | --- | --- |
| F1 / 10-1 | `PlayerSessionBindings` 登记有界精确清理身份；投递失败保留重试资格、在途合并；新登录撤销旧清理权限。投递及完成通知在锁外，资料删除仍在 player lane | 真实全局预算与 SPI 拒绝探针均得到 `retryLogout=true, sessionPresent=false, playerRetained=false` |
| F2 / 12-9 | `TargetNames` 检查生成类型与全部生成包前缀；`JavaCompilationUnitNames` 检查每单元声明、真实导入及同包引用，后者预检后才省略冗余 import | 两个反例均 `validate=1, generate=1` 且未创建输出；`Class` 控制组 `validate=0, generate=0, javac=0` |

失败反例现在绑定 `DSL_VALIDATION_FAILED`，错误包含冲突的完整名称。没有硬编码 `BoxDTOCodec` / `codec` 黑名单；无引用的合法跨包同名仍可生成并编译。同包引用纳入预检还防止自定义 DTO/codec 同包时，外部 runtime import 遮蔽用户枚举。

## 回归覆盖

- `PlayerLogoutRetryTest`：真实 Actor 全局容量拒绝，恢复后重试清理成功，再次请求 false。
- `PlayerLogoutDispatchTest`：SPI 同步抛出、异步拒绝恢复；重复观察者合并为一次物理清理；取消单个观察者不取消其他结果。
- `PlayerSessionBindingsTest`：预算满额拒绝不解绑；错误 account/uid/session 不得重试；失败尝试迟到不再有清理权限；替换会话再次退出后旧清理仍无权删除；账号复用不被旧 UID 清理解绑。
- 既有 `PlayerLogoutHandoffTest`：替换登录保留原退出 true 的契约并保护新资料。
- `CodegenValidationBoundaryTest`：两原反例、额外 runtime import 冲突均在 validate/generate 写盘前拒绝；`Class` 及 JDK 注解/异常枚举继续实际 javac 编译。
- `JavaNameLayoutTest`：自定义 DTO 后缀、深层包前缀、分输出目录、保留手工文件、同包引用遮蔽、无引用合法同名及移动 codec 包后的合法枚举；正例使用真实 JavaCompiler。
- `JavaCompilationUnitNamesTest`：不同导入同简单名、BO 声明/实现名与导入冲突、重复相同导入。

修复前先运行正式回归，确认 3 个预期失败、0 个错误，记录于 `red-tests.log`。实现后先通过 11 reactor 的目标依赖闭包 `-Pquality verify`，再执行全仓门禁。首次目标质量执行发现新增测试两处通配符导入，修正后通过；同包引用边界补全后重新通过 `target-quality-v2.log`。

## 门禁与证据

**F1/F2 本轮修复验收通过，已执行的回归未发现新故障。**

| 门禁 | 本轮结果 |
| --- | --- |
| 目标 `-pl zero-player,zero-codegen -am -Pquality verify` | 11 reactor 通过，退出 0；最终日志 `target-quality-v2.log` |
| 全仓 `-Pquality,benchmarks verify` | 63 reactor 通过，退出 0；含 Checkstyle、PMD、SpotBugs、JaCoCo 及 benchmarks 构建，耗时 5 分 14 秒 |
| 新鲜测试 | 47 模块、335 suite、1298 测试：1297 通过、0 失败、0 错误、1 真实 Redis 条件跳过；按本次 Running 名称及 XML 修改时间统计，排除 122 份非本次 XML |
| `ZeroArchitectureGuard` | 61 模块、22 规则、0 违规/警告，退出 0 |
| `VerifyCodegenModes` | 独立 JVM 8 个模式/退出码/手改保护用例通过，退出 0 |
| `VerifyPublicApiCompatibility --check` | 退出 1，仅历史 `ZeroReader.readPresenceBits()` 删除；没有新增 API 基线失败，未改基线 |

上述 1298 测试较前次全仓复核新增 18 个测试、5 个 suite。原玩家探针重放使用当前编译类，生成器反例重放使用当前 fat jar，合法控制组使用 JDK 21 javac；不是复用旧探针结论。

证据目录：[任务归档](../../tasks/archive/20261009-audit-f1-f2-fixes/VERIFY.md)，包括原探针副本与当前日志、CLI 错误、javac 输出、各门禁完整日志/退出码、只计本次运行的测试 XML 索引、修改前备份与最终源码摘要。历史报告仅追加后续链接，保留原失败结论。

## 行为边界与结论口径

本轮证明 F1/F2 原反例及上述边界已修复，不等于全仓绝无 bug，也不把原报告中延后能力计为已实现。独立 `loadPlayer` 迟到加载（C2）、既有逻辑回调等待约束（C1）仍按历史条件风险处理；没有进行外部中间件、跨 OS/跨语言运行、长稳压力或 JMH 性能测量。

待清理上限 4096，达到上限时在解绑前拒绝。调用方必须保留原身份并在调度恢复后重试；无后台自动重投，关闭服务或永久停止 scheduler 后无重试保证。公共签名不变，新增容量错误及返回值细节见 [0.x 迁移说明](../migrations/20261009-audit-f1-f2.zh-CN.md)。
