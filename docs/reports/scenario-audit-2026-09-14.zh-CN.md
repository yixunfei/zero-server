# 场景接入审查与后续优化方案

本页保留 2026-09-14 审查时的发现、验证与建议，对应修复随后纳入 `f8ed623`。文中的“当前工作树”和“没有提交”等描述指验证时点；最新使用步骤见[快速上手](../quickstart.zh-CN.md)，后续工作统一见[优化路线图](../optimization-roadmap.zh-CN.md)。

日期：2026-09-14；基于当前开发工作树。状态：`development-preview / productionReady=false`。

## 结论与覆盖范围

现有中立 runtime、独立集成和 Adapter 已能支撑按需依赖，不需要重建模块体系。主要问题在装配失败边界、脚手架覆盖、运行档位与场景文档。最小 runtime 实际仅 3 个框架制品，事件/Actor 组合 7 个，Kafka RPC 组合 15 个；无需引入全部依赖。

本轮自动检查覆盖根 Reactor 56 模块。重点源码审查涉及 runtime 装配、配置、资源、启动健康、集成边界、生成器、消费示例和三种接入场景；不是对全部源码逐行或全部并发交错的穷尽证明。扫描了 750 个主 Java 文件，未发现超过 1500 行的单个主源码文件；最大文件为 1426 行的 LocalManagedScheduler，后续应按调度职责审查，不能只按长度机械拆分。

## 已修复问题

| 问题及影响 | 修复 | 回归证据 |
| --- | --- | --- |
| P1 组件 A 取得资源后，组件 B 入口预算耗尽，绕过回滚，调用者拿不到 runtime 无法释放 | 将入口预算检查纳入资源回滚事务 | `RuntimeFailureMatrixTest.deadlineBetweenProvidersMustRollbackPreviouslyAcquiredResources`；原实现 close 列表为空，修复后恰好关闭 A，B 未创建，报告无待关闭资源 |
| P2 同步健康检查超出总启动预算，最后一个探针仍可把运行时推进 RUNNING | 扣除 check/转换耗时，等待后再次检查截止时间 | `finalSynchronousHealthProbeMustNotOutliveStartupBudget`；原实现未抛异常，修复后 timeout/FAILED/回滚 |
| P2 diagnose 冻结集合选择 Builder，之后追加贡献者抛 UnsupportedOperationException | Builder 保留可变容器，快照构造器独立冻结副本 | `RuntimePlannerTest.diagnoseMustNotFreezeFurtherContributionsOrChangeEarlierPlans`；追加可用，旧计划不变 |
| P2 文档存在 standalone 档位，但 ProductionAssembly 拒绝；生成装配又硬编码 external-test | 补齐档位入口，从配置选择运行档位 | `ProductionAssemblyTest`；外部消费者分别规划 standalone/external-test/production |
| 接入成本：已有四种 Adapter 无法从脚手架选取，多数据来源隐式选 Redis 作为 main | 新增 kafka/nacos/mongo/postgresql，替换对应本地实现，多来源按名称绑定 | 25 个独立消费者；实际 Maven classpath、SDK 缺席/存在和 provider 图匹配 |
| 示例直接创建线程池，生成工具进入业务依赖 | TCP 示例使用 ZeroRuntimeExecutors；codegen 移入构建插件 | 真实 Socket→Netty→生成 BO 响应，业务线程名及会话关闭验证通过 |

运行时三项 bug 均先新增回归用例，确认原实现失败后再修复。未改变 Actor 模型、RPC 字节格式、存储格式、鉴权决策或已有安全/GM/恢复工作的实现。

## 使用者视角

| 场景 | 当前能做 | 仍有成本或缺口 |
| --- | --- | --- |
| 单体原型 | 一条生成命令取得协议/BO 本地业务闭环；空白 runtime 无中间件 | 原型 main 是一次 smoke 后退出；长期监听、真实登录与重连需要应用接入 |
| 中心—逻辑 | 共享接口的本地 RPC 示例；选择 Kafka 即可取得独立远程传输组件，不强制 Nacos/数据库 | 正式远程 Adapter 目前只有 Kafka；没有免 broker 的轻量双进程方案；服务绑定、topic、实例路由仍需显式配置 |
| 分布式治理 | 独立选择发现、RPC、数据、缓存、日志、监控和安全入口；无资源 diagnose/plan | Adapter 组合不等于服务治理成品；恢复接入、持久审计、TLS 实装、可靠落库、排空和容量证据仍不完整 |

`NetworkRuntime` 只是连接生命周期集成，不能把它误称为 TCP listener。脚手架暂未提供 net 选择；当前入口可从真实 TCP 示例开始，生产策略必须由应用显式注入。

配置边界也已澄清：`configSource()` 覆盖 typed schema，业务 Provider 应读 `context.config()`；它不会把所有任意键写回 `RuntimeBasics.CONFIG`。

生成的命令行诊断只显示所选组件、状态和缺失键；完整 provider/驱动类型报告仍可通过 Java 诊断 API 取得，普通接入流程无需阅读内部实现类名。

## 后续实施顺序与验收

每项作为独立变更推进，保持核心无具体中间件依赖；公共 API、线程、协议与存储变化继续按仓库规则确认方案。

| 顺序 | 优化项 | 实施切片与依赖 | 完成验收 |
| --- | --- | --- | --- |
| P0-1 | 从 smoke 到可长期运行的最小服务入口 | 可选 net 集成和 listen/run/stop，显式区分本机原型策略与生产安全策略；保持普通 test 无外部副作用 | 新生成项目真实 TCP 请求进入 BO；端口冲突 fail-fast；退出释放端口/执行器；不带 Kafka/DB；Windows/Linux/macOS CI 证据 |
| P0-2 | 简单中心—逻辑双进程套件 | 拆出共同契约、中心实现和逻辑客户端；先复用 Kafka，接入实例注册/摘除、排空及身份校验 | 两个真实进程完成请求/响应；进程退出/超时/迟到响应/重复请求可观测；只有配置及 transport 改变，业务接口复用 |
| P0-3 | 完成治理最短闭环 | 承接现有 security、resilience、GM 契约，优先接到真实网关与一种 Adapter；逐种扩展 | TLS 证书装载与轮换、拒绝明文、恢复状态、积压上限、审计持久化与查询在隔离环境实测；失败不伪装成功 |
| P1-1 | 免 broker 的轻量 RPC Adapter | P0-2 业务契约稳定后，先确认 TCP/HTTP 传输方案、超时/背压/幂等边界；独立 Adapter 工件 | 同一中心/逻辑契约在本地、轻量传输和 Kafka 下通过契约套件；轻量路径 classpath 无 Kafka/Nacos/数据库；有最大 in-flight 与断连测试 |
| P1-2 | 业务模板与基础设施正交组合 | 从生成的大 Application 内抽出应用服务、BO 和装配职责；不是新增万能容器；按需生成网络/RPC/Repository 接线 | 各业务模板在 local 与一种真实后端复用业务类；本地测试无中间件；真实测试显式启用；清单始终对应有效 provider |
| P1-3 | 数据可靠性与故障恢复 | 在现有 Repository/缓存边界验证脏数据、批量刷写、背压、未知写结果及恢复对账 | 进程重启/网络分区/池耗尽不静默丢写；每种 Adapter 有恢复和幂等证据，业务状态归属保持不变 |
| P1-4 | 可运行的观测与运营包 | 独立 Prometheus HTTP 入口、生产日志 sink、trace、GM 审批与持久审计；复用现有标准 | 报表能串联入口→RPC→数据→审计；指标标签有界、敏感信息脱敏；最小应用无该包也可运行 |
| P2 | 性能、长稳和发布证据 | 对实际工作负载做容量、p95/p99、内存、分区与滚动升级测试，接入 CI/发布检查单 | 提供环境、版本、负载与原始数据；证明回滚/备份恢复；证据完成前保持 productionReady=false |

优先降低“生成之后还缺一段启动胶水”和“双进程不知道如何绑定服务”的成本；不把更多必选中间件或隐式扫描塞入核心。

## 验证记录

命令均在 Java 21.0.4、Maven 3.9.8、Windows 执行。开始时默认 PATH 是 Java 17，验证只在子进程切换 JDK，不修改机器全局配置。

| 验证 | 结果 / 证据 |
| --- | --- |
| 架构静态检查 | 56 模块、20 规则通过；`target/scenario-audit-architecture.log` |
| 全仓基线 test | 605 测试，0 失败/错误/跳过；`target/scenario-audit-baseline-test.log` |
| 修复前故障复现 | 资源泄漏、健康预算及 Builder 冻结分别失败；`target/scenario-audit-regression-before*.log` |
| 全仓 quality verify | 611 测试及 Checkstyle/PMD/SpotBugs/JaCoCo 流程通过；`target/scenario-audit-quality.log` |
| 最后追加档位/生成变更质量检查 | 受影响模块及上游 quality verify/install 通过；`target/scenario-audit-final-focused-quality.log` |
| 独立消费者 | 5 个消费者、6 测试通过，包括新中心业务调用；`target/scenario-audit-consumers.log` |
| 真实 TCP 示例 | 测试和运行通过，响应路由/业务执行器/会话关闭正确；`target/scenario-audit-tcp.log` |
| 最终本地集成 | 612 个测试执行（含单测），0 失败/错误/跳过；`target/scenario-audit-integration.log` |
| 最终生成消费者 | 25/25 通过；外部组合分别规划三种档位；`target/scenario-audit-generated-final.log` |
| 默认业务模板 | 7/7 生成、检查、测试和运行通过；`target/scenario-audit-scaffolds.log` |

日志是当前工作树的本地证据，不代表已发布版本。未运行真实 Kafka/Nacos/数据库服务、跨平台 CI、多进程故障恢复、容量/长稳或完整 Stage 0 full；质量/本地集成/生成消费者分别执行。已保留本轮开始前的暂存、未暂存与未跟踪修改，没有提交、推送或发布。

接入操作见[场景指南](../quickstart.zh-CN.md)，行为调整见[0.x 迁移说明](../migrations/20260914-scenario-composition.md)。
