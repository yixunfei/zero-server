# 全仓可靠性与性能复核（2026-10-05）

## 范围和结论

基于 `zero/concurrency-async-performance-audit` 分支、HEAD `a4d254ae23242acfa552fa48a4a04a3261198f87` 与当前未提交工作树，复核根 Maven reactor 的 61 个模块及相关门禁。环境为 Windows 11、JDK 21.0.4、Maven 3.9.8。工作树本来包含多轮并发、IO、数据和性能专项修改，本轮保留这些改动；[前轮性能报告](performance-issues-20261005.zh-CN.md)、[反馈专项](feedback-hardening-20261004.zh-CN.md)、[并发专项](concurrency-async-performance-20261003.zh-CN.md)记录其逐项复现和修复，本报告不重复归属那些代码。

按核心执行域与游戏状态、网络与 RPC、数据与缓存、运行时与可观测性、脚手架与业务接入分组，检查近期修改的契约、测试、生命周期、同步/异步边界和模块依赖；再以全仓构建覆盖所有模块。静态扫描没有发现新增占位实现或超过 1500 行的生产 Java 文件；Actor scheduler 的 `join()` 仅消费已经完成的 future。本轮新增两处可复现的 `zero-monitor` 问题和一处 API 门禁误报。没有证据支持当前代码已被逐行证明无缺陷，也没有生产容量或跨节点故障的完整验证，不能据此宣布可直接停止可靠性审查。

## 确认问题与处理

| 触发条件 | 原行为 | 处理与边界 |
| --- | --- | --- |
| 本地告警首次发布时 sink 抛错 | 规则已进入去重集；即使条件持续满足，后续评估永久不再重试，直至恢复。 | 全部 sink 成功后才标记已发布；下次评估重试整条事件。前序 sink 已接收或投递成功后抛错可能重复投递，调用方需容忍重复。 |
| 注册表保留同一指标名和标签的多条历史，进行单次 Prometheus 抓取 | 同一次抓取输出多条相同序列，可能产生冲突。 | 按样本时间选最新；时间相同选后记录。每个序列只输出一条，本地有界历史不变。所有历史样本仍逐条校验，较旧样本的非法时间不能被聚合掩盖。 |
| `DefaultGameRuntime` 的方法级 `synchronized` 改为独立锁后运行 API 符号门禁 | `javap` 文本比较将三个方法报告为删除，即使方法描述符仍存在。 | 符号比较忽略该修饰符，不重新生成 baseline。锁语义仍须人工审查，门禁不覆盖线程安全。 |

0.x 用户可见行为和消费者调整见[迁移说明](../migrations/20261005-full-reliability-review.zh-CN.md)。监控修复不新增强制绑定的 sink、聚合器或线程池；自定义 `MetricRegistry` 和 `AlertSink` 仍由业务选择。

## 验证证据

- 最后一次 `mvn -Pquality,benchmarks,integration-tests verify -q` 使用 JDK 21，退出码 0。以 14:55 后新写入 XML 计，Surefire 288 个 suite、1097 项测试，0 failures、0 errors、1 skipped；跳过项为既有 Redis KCP 条件测试。新生成 Checkstyle 61、PMD 60、SpotBugs 60 份报告，质量门禁均通过。JaCoCo profile 未设置覆盖率通过阈值。
- `zero-monitor -am verify -Pquality -q` 在补齐时间边界后通过；故障注入验证首次和后序 sink 失败后重试、成功后去重、重复投递边界、样本时间与同时间戳选择、非法旧样本仍拒绝、注册表历史保留，并通过 HTTP `/metrics` 断言每序列只输出一条。
- `ZeroArchitectureGuard`：61 模块、21 规则、0 违规、0 警告；`ZeroFrameworkBoundaryGuard`：5 个保护根、7 个玩法标记检查通过。JDK 21 下 API 符号门禁保护 5 模块通过，独立 API 消费者编译通过。首次消费者命令误用系统默认 JDK 17 被编译器拒绝，已在 JDK 21 重跑成功；不计为源码故障。
- 本机 Docker Redis、PostgreSQL、MongoDB、Kafka 外部集成测试通过。Kafka `KafkaReplyIsolationExternalIT` 在真实 broker 上完成双实例独立 reply 路由；数据驱动外部 suite 和现有 batch/page、Redis 并发测试结果无 failures/errors。服务在验证前均为停止状态，本轮只启动命名服务，验证结束后恢复停止。
- `git diff --check` 退出码 0；已有三个无关文件报告 CRLF 转换提醒，未修改其内容。

## 性能与剩余边界

Prometheus 导出现在对历史样本做一次线性扫描，并按序列保留最新样本，时间复杂度为 O(样本数)，额外空间为 O(不同序列数)；`InMemoryMetricRegistry` 默认历史上限为 4096。没有同机、同负载的前后性能基准，因此不声称吞吐或延迟提升。全仓的 benchmark profile 在此次构建中完成编译与 smoke，不构成生产容量证明。

未覆盖跨机网络分区、broker/数据库故障切换、长稳内存与队列积压、真实业务规模下的 P95/P99 和 SLO。现有架构守卫只检查明确编码的依赖边界，不能排除未建模的锁、时序或业务缺陷；上述部分需要可复现部署、业务流量与验收指标后继续验证。生产日志 sink、Prometheus TLS/认证部署策略和跨节点恢复能力仍以各专题文档的 `productionReady=false` 边界为准。
