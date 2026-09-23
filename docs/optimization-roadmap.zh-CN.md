# zeroServer 后续优化路线图

更新日期：2026-09-18。适用版本：`0.1.0-SNAPSHOT`，`productionReady=false`。本页统一维护未完成工作；当前实现以[能力矩阵](capability-matrix.zh-CN.md)为准，已发生变更见 [Changelog](../CHANGELOG.md)。

## 已有基础

后续工作应复用以下实现：

| 领域 | 可复用基础 | 仍需完成 |
| --- | --- | --- |
| 上手与装配 | Wrapper、统一入口、typed schema、ownership 升级/回滚；local TCP Server 与 Kafka 三模块示例 | net 生成装配编译修复、TCP 业务响应完善、双进程外部验收和治理 |
| 入口安全 | `zero-security` 认证/重放/TLS 材料/可信来源契约，TCP 安全策略 | 真实证书加载和轮换、完整传输接入 |
| Adapter 恢复 | 健康/恢复/预算 SPI、状态机、有界恢复编排与注入测试 | 各真实驱动的周期探测、恢复和故障证据 |
| GM | DSL、授权门、REST 请求处理适配、审计/幂等/break-glass 端口和内存实现 | HTTP 监听与真实身份、Kafka 接线、持久审批/审计/幂等 |
| 游戏组件 | Room、AOI/状态同步、帧同步、NPC、榜单、世界分片的本地最小实现 | 分布式所有权、持久化、真实广播和容量 |
| 可观测性 | 结构化日志、安全门、低基数指标、Prometheus 文本和显式 HTTP Endpoint | 生产 sink、认证/TLS、完整 trace 与告警通道 |
| API 检查 | 五个核心模块的 API 基线、签名检查和独立消费者编译 | 二进制兼容、配置 key/协议 ID/provider ID 演进检查 |

## P0：优先打通可运行的服务链路

保留既有 P0 编号，便于对应[历史迁移记录](migrations/README.md)；新增中心—逻辑套件记为 P0-6。

| 编号 | 下一步 | 验收条件 |
| --- | --- | --- |
| P0-1 运行入口 | 先修复生成器无参 `NetworkRuntime.module()` 与 policy/limiter API 不匹配，再验收已有 TCP Server；补齐响应协议、异常路径与安全策略接线 | 生成项目真实 TCP 请求进入 BO；端口冲突失败；退出释放端口与执行器；最小应用不带 Kafka/DB；三平台 CI 有实际运行证据 |
| P0-6 中心—逻辑套件 | 已有共享契约、center、logic 三项目及验收脚本；完成真实 Kafka 双 JVM 验收和异常场景 | 两个真实进程完成请求/响应；实例注册/摘除、排空、超时、迟到响应、重复请求有可观察结果；业务接口可复用 |
| P0-2 安全入口 | 将现有安全 SPI 接到真实 TLS 和 HTTP/RPC 边界 | 证书装载/轮换、拒绝明文、可信代理、认证失败、重放和请求限流均有测试；缺策略时 fail-closed |
| P0-3 Adapter 恢复 | 先选一种真实 Adapter 接通健康→降级→恢复，再推广 | Kafka 重启/rebalance、Nacos 订阅恢复、数据库连接重建及未知写结果按各自语义验证；重试/in-flight/积压有上限 |
| P0-4 GM 运营 | 实装 HTTP/Kafka 入口和持久化 provider | 真实身份与授权、审批事实、审计查询/归档、幂等 claim、break-glass 全链路实测；非法请求和解析失败也可安全归因 |
| P0-5 API 演进 | 在现有 bootstrap gate 上补二进制及非 Java 契约检查 | 破坏性 API、配置 key、协议 ID、provider ID 变更有 CI 失败证据与迁移说明；最小依赖消费者仍可编译 |

推荐先完成 P0-1 和 P0-6，明确业务入口与进程边界；再在这一真实链路中推进 P0-2～P0-4。P0-5 随公共契约变化维护，不必等待所有治理能力完成。

## P1：可靠性与接入成本

| 方向 | 实施范围 | 验收条件 |
| --- | --- | --- |
| 免 broker 的 RPC | P0-6 契约稳定后评审 TCP/HTTP 方案，独立 Adapter | 同一业务在 local、轻量传输、Kafka 下通过契约测试；轻量路径无 Kafka/Nacos/DB；有断连、超时和最大 in-flight 验证 |
| 配置与组合 | 从 typed schema 导出机器可读配置说明；业务模板与基础设施独立组合 | key/env/type/default/sensitive/profile 一致；缺失和冲突可定位；本地测试无需外部服务；有效 provider 与 Maven 依赖一致 |
| 协议工具 | 协议 ID/字段顺序/nullable/枚举检查、生成差异预览、客户端回归 | 错误有文件/行列；已有手写 BO 不被覆盖；不同语言使用同一组协议用例 |
| 数据可靠性 | 脏数据、批量刷写、失败保留、队列预算、未知写结果与恢复对账 | 重启、网络分区、池耗尽不静默丢写；每种 Adapter 提供幂等和恢复证据；状态修改遵守 Actor 所有权 |
| 观测与运营 | 生产文件/Kafka sink、Endpoint 安全接入、trace、告警 | 能关联入口→RPC→数据→审计；标签和队列有界、敏感字段脱敏；最小应用可不引入运营包 |
| 发布与恢复 | 制品签名/SBOM、迁移实例、备份/PITR、滚动升级和回滚；材料工具改为公开检出基准并报告真实缺失计数 | 在隔离环境实测恢复，记录 RPO/RTO、校验和失败边界；发布步骤可复现；缺失必需材料不能被摘要掩盖 |
| 性能与长稳 | 按真实负载分层测量协议、Actor、网络、RPC、数据、缓存和场景 | 记录代码版本、环境、p95/p99、内存、分配、吞吐、错误/拒绝率；阈值依据重复测量建立 |

## P2：按使用场景扩展

| 方向 | 依赖与边界 |
| --- | --- |
| 游戏组件分布式化 | 在本地最小模块上扩展跨服、所有权、迁移、匹配/结算；不把具体游戏规则塞入核心 |
| 高级调度 | 有实际需求后评审 cron、持久任务、集群唯一执行与高频 tick |
| 更多网络传输 | 独立评审 WebSocket/KCP/UDP 的安全、可靠性与背压 |
| 热更 | 优先配置发布；CGLIB/ClassLoader 扩展须具备签名、备份、回滚和集群同步 |
| 部署工具 | 在 Compose 基础上补多实例部署与运维示例，验证配置、排空和恢复 |

## 实施与验证方式

每次选择一个可独立验收的能力，先审查现状和公共边界，再实施、验证并更新能力矩阵、迁移说明与 Changelog。贡献流程见[贡献指南](../CONTRIBUTING.md)。一次 focused test 或单节点恢复成功只能证明对应场景。

```bash
java scripts/ZeroLocalDoctor.java
java scripts/ZeroArchitectureGuard.java
mvn -B -ntp test
mvn -B -ntp -Pquality verify
mvn -B -ntp -Pintegration-tests verify
```

生成器改动追加 `VerifyGeneratedCompositions` 和 `VerifyLocalScaffolds`；API 改动执行[兼容门禁](operations/api-compatibility-gate.zh-CN.md)；性能工作按[性能方法](operations/performance.zh-CN.md)准备负载；发布工作使用[检查单](operations/release-checklist.zh-CN.md)。真实中间件测试必须显式准备隔离环境。

`ZeroFrameworkGapLedger --allow-missing-evidence` 可查看历史证据台账，但其中有未公开的维护者材料，输出不能代替本页当前状态或测试执行。历史场景审查见[报告索引](reports/README.md)。

重新评估生产就绪前，至少需要当前代码的完整本地验收、真实 Adapter 故障恢复、安全和 GM 运营、可靠落库、观测闭环、容量长稳以及发布回滚证据。缺项继续保留 `productionReady=false`。
