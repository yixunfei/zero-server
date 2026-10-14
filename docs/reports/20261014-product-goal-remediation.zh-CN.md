# 产品目标复查修复与玩家链路验收

日期：2026-10-14。版本：`0.1.2-SNAPSHOT`。起点：`b61a8bf3377662211e4cbd14b7bcfb7dcdee9ac6`。实施分支：`xunfei/product-goal-remediation`；本报告描述工作区修改，没有发布或创建 Git 提交。

本次完成用户确认的 D01–D08，并继续贯通玩家类型化异步响应、可信上下文、正式档案仓库、Actor 周期保存和每日奖励通知恢复。依据为[二轮复查](20261012-product-goal-recheck.zh-CN.md)，所有破坏性方案均经用户明确批准。原报告的其他产品目标仍按[能力矩阵](../capability-matrix.zh-CN.md)标注，`productionReady=false`。

## 修复与回归对应

| 编号 | 最终实现 | 实际验证 |
| --- | --- | --- |
| D01 | Repository 示例采用 ProductionAssembly 默认受管远程 IO | local/MongoDB/Redis/PostgreSQL 四条公开路径均得到 amount=15、version=2；6 项接线回归通过 |
| D02 | Center/Logic 两端复用现有 HMAC assertion/verifier，启动前校验配置并保留 RPC reply topic | 身份缺失、签名篡改、过期及双端接线回归；真实 Kafka 双 JVM 注册、heartbeat、注销、active=0、成功退出 |
| D03 | NPC 有预算的轮转续扫，不预先复制全部候选 | 100 NPC、每轮预算 3 的公平性、增删、零预算与选择开销边界回归 |
| D04 | 便利内存仓库对齐标准 local 初始版本、缺记录旧写和批量错误 | 同套 CRUD 覆盖负版本/MAX、创建更新、删除后陈旧写、并发 CAS；批量保留 VERSION_CONFLICT 与原因链 |
| D05 | World/Ranking 移除自动 String 序号目录与入口，调用方使用显式正序号和确认水位 | 容量倍数 churn、千轮退役及全部内部目录回归；旧序号拒绝、新序号可用；示例、脚手架、基准迁移 |
| D06 | NPC 失败事件携带统一异常、原 cause/ErrorCode 和当前 trace | 单 NPC 失败隔离、其余继续执行；行为失败和观察端失败分别可观察 |
| D07 | CachePolicy 全部构造入口明确拒绝尚未实现的 stale=true | 两种构造拒绝 true，默认 false 与既有缓存故障回归通过；未宣称实现陈旧值回退 |
| D08 | Ranking 准入成功后才发布 Board；赛季查询显式携带代际 | 失败评分/初始转换不污染目录；合法退役、未来/当前/已退役及代际不匹配语义回归 |

序号分配与确认由调用方负责，World 迁移、Ranking 赛季和评分请求使用各自作用域；确认不能跳过在途请求。Repository 批量非事务等边界见[迁移说明](../migrations/20261014-product-goal-remediation.zh-CN.md)。

## 玩家链路

Java codegen 生成 `BO.Async<R>`、异步 dispatch/register 和类型化帧端点。AsyncProtocolEndpoint 在副作用前验证完整 payload，等待真实 CompletionStage 后编码响应 DTO；错误使用标准错误帧。显式 response DSL、DTO 参数和展开参数均编译实际生成源码后验证。

RequestExecution 从已验证 SecurityContext 捕获身份、trace、RPC 选项、deadline 和取消状态。ContextualActorScheduler 保持原 payload/lane，框架入口和交付回调显式恢复、清理上下文；业务自建异步回调仍需显式恢复 scope。真实虚拟线程 Actor 到 RpcClientFactory 的 signed transport verifier 回归验证可信身份、非默认 reply topic 和预算。

ActorRequestExecutor 等待物理业务完成后才释放 lane。取消、断连、身份过期和 deadline 阻止后续准入及成功交付，不撤销已发出的保存。排队期限、迟到完成、取消后第二次执行及后台空上下文有确定性回归。

local 模板返回 loginResult/sceneResult，players 角色加载正式 PlayerProfileRepository，默认 local，唯一外部数据源默认绑定它，也可显式覆盖。真实 PostgreSQL 生成消费者先写入随机档案，再经 LocalPlayerService 读取同一名字和版本并删除记录，证明业务实际消费 catalog。模板仍是固定 UID 的 loopback 原型，token 字符串不构成账号认证。

BoundPersistentEntity 保存不可变状态，更新和保存确认通过同一 binding executor 回到所属 lane。DefaultPersistenceManager 保存成功后确认版本，保留期间新脏代际；确认失败先重试确认，不重写已提交快照。ManagedPersistenceScheduler 复用完成感知 PeriodicAsyncTask，失败可观察；个别句柄取消失败仍继续最终排空。Actor 周期保存、版本推进、确认竞争及关闭均有受管执行器回归。内存更新接受不等于奖励/扣款的耐久提交。

## 每日奖励与恢复

同一 DailyRewardBO 服务 local 和经 SecurityChain 认证的真实 loopback TCP。可信身份必须匹配 uid 并拥有领取权限。TCP 使用框架生产生命周期、握手就绪帧和每连接取消范围；传输失败可由 health 查询并在关闭时传播。测试认证器使用随机 token，正式认证供应商由应用提供。

schema/codec 3 将库存、领取日期、首次请求键、待通知状态和通知 trace 一次 CAS 提交。随后失效缓存、发送稳定 ID daily-reward-uid-day 事件并 CAS 确认。重复领取不增加库存，未知写结果不盲目重试。每玩家一个待通知槽，未确认旧通知时拒绝下一日领取。

受管任务每页 64 条扫描并在玩家 lane 重放，某条失败保留状态，其他条仍处理并汇总失败。发送成功但确认失败可能重复投递，消费者按事件 ID 去重；这是至少一次配方，没有分布式 outbox claim 或跨资源 exactly-once 承诺。

真实 PG 验收先持久化库存 100 和待通知，关闭 runtime/连接池后重建，读回库存/日期；重复请求返回 DUPLICATE，周期重放清除待通知并验证事件 ID 去重。清理自己的随机 uid，不重置共享数据库。这是同 JVM 重建，不是杀进程、跨进程 owner 恢复或数据库故障切换测试。

## 验证环境与结果

Windows、Java 21.0.4（D:/env/jdk21）、Maven 3.9.8（D:/env/apache-maven-3.9.8）。JAVA_HOME/PATH 只在命令进程内设置；共享 Maven 串行构建。

以下为本轮实际结果，重复执行不累加为独立测试总数。日志路径均相对仓库根目录。

| 命令或验收入口 | 结果 | 日志/证据 |
| --- | --- | --- |
| `mvn -B -Pquality install` | 12:25:25 BUILD SUCCESS；1369 项，失败/错误/跳过均 0；Checkstyle、PMD、SpotBugs、JaCoCo 通过 | target/product-goal-final-reactor-quality.log |
| `mvn -B -ntp -Pquality -pl zero-codegen,zero-ranking install` | 最后两项生成契约新增后，codegen 128 项、ranking 25 项及质量门禁通过 | target/product-goal-final-focused-quality.log |
| `java scripts/VerifyGeneratedCompositions.java` | 26 消费者 clean 测试、main/离线诊断、精确依赖闭包与 classpath 隔离通过 | target/product-goal-generated-compositions.log |
| `java scripts/VerifyLocalScaffolds.java --outputDir target/product-goal-scaffolds` | 7 业务脚手架 inspected/tested/ran=true | target/product-goal-scaffolds.log |
| 最新 local + net 生成消费者 test | 9 项通过：typed DTO、真实 TCP、错误/端口释放、档案 catalog；main maxProtocolId=90202 | target/product-goal-local-verified-test.log |
| World/Ranking/business-slice 分别 `mvn -B -ntp -Pquality -f examples/<name>/pom.xml verify exec:java` | 三个独立项目质量、测试和 main 均通过 | target/product-goal-example-{world-shard,ranking-season,business-slice}.log |
| Repository 示例 test / 各 backend 的 test exec:java | 接线 6 项；local/mongo/redis/postgresql 实际读写及记录清理 | target/product-goal-repository-wiring-tests.log、target/product-goal-repository-{local,mongo,redis,postgresql}.log |
| `./scripts/VerifyCenterLogicKafka.ps1 -BootstrapServers 127.0.0.1:29092` | 双 JVM 签验/注册/heartbeat/注销/active=0；进程和清理退出码 0 | target/product-goal-kafka-external.log；run 50f3aede7ddc483fb23642a522159b39 的 manifest |
| local + net,postgresql 消费者 `mvn ... '-Dtest=*Test#playerLoadsProfileFromSelectedRepository' test` | 1 项：players→postgresql，真实写入及业务加载 name/version=1，随机记录清理 | target/product-goal-profile-postgresql-test.log |
| `mvn -B -ntp -f examples/business-slice/pom.xml '-Dtest=*Test,DailyRewardPersistenceExternalIT' test` | 最后 TCP 修改后 11 项含真实 PG 恢复，失败/错误/跳过均 0 | target/product-goal-reward-postgresql-final.log |
| `mvn -B -ntp -Pquality -f zero-benchmarks/pom.xml package` | 基准与序号协调器质量/编译通过 | target/product-goal-benchmarks-quality.log |
| Ranking update size=100000 / 1 fork / 1s；Shared mixed 同榜 4 线程 100% 写 / 1 fork / 2s | 短时生命周期冒烟完成，无失败 iteration；不用于性能结论 | target/product-goal-ranking-{lifecycle,shared-lifecycle}-smoke.log |
| `java scripts/ZeroArchitectureGuard.java`、`java scripts/ZeroFrameworkBoundaryGuard.java` | 61 模块/22 规则/0 违反/0 警告，框架模板边界通过 | target/product-goal-architecture.log、target/product-goal-final-boundary.log |
| JavacTask 只读语法/长度审计 | 72 修改 Java 文件、560 方法，文件>1500/方法>100 的发现 0 | target/product-goal-source-limits.log |

完整 reactor 在最后补充两项 codegen 测试、基准和 TCP 诊断前执行，之后复跑受影响模块、示例与生成消费者。Stage 0 full 本轮只查看 --plan，相关子检查按上表分别执行，没有声称 full 一键验收已运行。

生成组合的外部 runtime main 仅离线诊断，不代表连接 26 组真实服务。真实连接证据只对应上表四种 Repository、Kafka、PG 档案与奖励。基准现在包括序号分配/连续完成确认开销，历史数字不是同口径基线。

PG 档案消费者由 `java -cp zero-codegen/target/zero-codegen-0.1.2-SNAPSHOT-all.jar group.zn.zero.codegen.scaffold.ProjectScaffoldCli --template local --components net,postgresql --projectName goal-profile-pg --packageName group.zn.zero.goalpg --outputDir target/product-goal-profile-postgresql --templateRoot templates` 生成，再执行 `mvn -B -ntp -f target/product-goal-profile-postgresql/pom.xml '-Dtest=*Test#playerLoadsProfileFromSelectedRepository' test`。ZERO_CONFIG_FILE 指向 target 下不含凭据的 properties，使用 zero.postgresql.url/username/table 与 zero.player.repositorySource=postgresql，密码单独从既有 secret 注入环境。

16 份相关主题、报告、迁移及归档 Markdown 的本地链接检查为 0 失效，日志 target/product-goal-document-links.log。

生成组合最初遇到旧产物所有权冲突，原目录整体保存到 target/generated-composition-verify-preserved-20261014 后重新生成成功。PG 档案首次配置误写 tableName，保留失败日志并纠正为正式键 zero.postgresql.table 后通过，没有修改 provider 契约。

## 外部环境与收尾

复用 M:/docker_space/containers/zero-flexibility-20260930 的 MongoDB 7.0.14、Redis 7.4.2、PostgreSQL 16.4，以及 zero-hardening-20260922 的 Kafka 3.9.1。Windows loopback 端口分别 17017、16379、15432、29092。沿用原账户/凭据，从既有 secret 加载密码，未打印或提交凭据。

本机 desktop-linux 引擎实际存储核验为 M:/docker_space/localai_4090/docker-desktop-data/wsl/disk/docker_data.vhdx。没有创建新镜像、账户或卷。结束后 scoped stop 并确认 exited，恢复原停止状态，保留容器、数据和凭据。Kafka stop 后容器码 137，测试两 JVM 和清理退出码为 0；停止过程不作为 broker 耐久性证据。两份 ENVIRONMENT.md 已记录复用与结果。

迁移、Changelog、模块图、线程/数据/协议文档、quickstart、能力矩阵与报告索引同步。任务归档至 [tasks/archive/20261014-product-goal-remediation](../../tasks/archive/20261014-product-goal-remediation/VERIFY.md)，附最终日志校验摘要。

## 尚未证明的范围

- 每日奖励真实双 JVM owner 迁移、杀进程恢复、跨节点通知抢占、生产账号认证；独立 Kafka 双 JVM 不能替代这些证明。
- 容量、长稳、复制集切换、网络分区或未知写结果故障注入；短 JMH 仅生命周期冒烟。
- 非 Java 客户端异步业务入口、更多玩法、H5/KCP/查询及其他 G 项未由本次链路全部完成。
- 未发布、部署或提交。按迁移说明更新调用方/客户端，需保留旧数据时显式转换。
