# Changelog

zeroServer 的重要用户可见变更记录在此。项目当前处于 `0.x` 开发预览阶段，公共 API、配置和协议仍可能发生破坏性变化。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## Unreleased

## 0.1.1 - 2026-09-29

### 修复与发布校正

- 固定 KCP TLS 登录示例测试的等待上限，避免慢速 CI 环境把正常票据下发判为超时。
- KCP 示例改为在已认证登录帧的处理阶段签发票据，消除连接建立与认证之间的时序竞态。
- TLS 握手成功后在所属 EventLoop 内立即启动生产网络生命周期，避免首个解密帧在生命周期初始化前到达而被丢弃。
- Codegen 浏览器冒烟测试改用结构化进程参数，正确处理含空格的浏览器与临时目录路径。
- 将脚手架默认版本、示例和用户文档的发布坐标统一到 `0.1.1`。
- 本版本不再改变运行时 API、协议格式、配置或持久化格式；首次接入迁移见 `docs/migrations/0.1.0.zh-CN.md`。

## 0.1.0 - 2026-09-29

### 生产网络生命周期最小可定制化

- production network lifecycle 不再隐式启用每 IP 默认限流或 fail-closed 安全链；调用方按需注入 `NetworkRateLimiter`、`SecurityChain`、`ConnectionLifecycleObserver` 和 `ProductionNetworkPolicy`。
- 心跳检查改为显式启用，保留握手/鉴权超时、入站预算和失败关闭等最小资源保护。
- 默认 no-op observer 跳过观测对象、单连接观测队列和执行器提交；显式 observer 仍按单连接顺序投递。
- 修复 KCP runtime 对可选监控类的隐式加载：最小消费者无日志/监控依赖也可启动；显式安装监控模块时仍接入采样。
- 移除私有默认 IP 桶及三项旧限流配置；网络 provider 不再依赖日志/监控能力，可选遥测工具的依赖改为 optional。显式安全链在生命周期组合根执行，直接构造和 runtime 入口保持一致。
- 0.x 行为迁移见 `docs/migrations/20260928-production-network-minimal-customization.md`。

### 2026-09-28 KCP 高级传输

- ZKCP/ZKCI 升级到 v1，提供可选 HMAC-SHA256、ChaCha20-Poly1305、AES-GCM、XOR/Reed-Solomon FEC 和可注册自定义策略。
- 增加 NAT challenge/response、`KcpClient.rebind()`、会话租约、冻结/迁移/代际 fencing，以及独立 `zero-net-kcp-redis` Lua 适配器。
- 这是 0.x 破坏性协议/API 变更；旧客户端必须重新登录并消费 v1 连接描述，详见 `docs/migrations/20260928-kcp-advanced-transport.md`。

### 2026-09-28 KCP 多场景与开箱即用优化

- 新增五类预设、分组配置与交叉校验、可信控制面连接描述、受管 Java 客户端及显式恢复/回退状态机。
- 新增可选 zero-runtime-kcp，一次安装接入命名配置、资源生命周期和低基数指标；提供独立 TLS/UDP main 示例。
- 按截止时间调度、主动发送、socket flush 合并、HMAC 暂存复用和按整帧实际引用释放预算，改善空闲与持续发送路径。
- 0.x KcpOptions 构造及默认帧上限变更、弱网测试和本机性能证据见[迁移说明](docs/migrations/20260928-kcp-scenario-optimization.md)。

### 2026-09-28 KCP 服务端闭环

- 新增独立 `zero-net-kcp` Adapter，使用 java-Kcp 算法与框架 Netty IO，提供真实可靠收发、帧编解码、有界队列与可观测错误。
- 支持已认证 TLS TCP 登录签发票据、HMAC 数据报认证、重放/地址校验、过期回收以及校验控制连接拥有权的显式 TCP 回退。
- 移除 KCP fail-fast 占位 API；新增真实 TLS/UDP Java 联调例子和故障测试。线格式、限制与 0.x 迁移见 [KCP 迁移说明](docs/migrations/20260928-kcp-support.md)。

### 2026-09-28 九项报告补充复核

- 房间消费者异常不再回滚已经发布的状态；默认历史容量仍为 1024，增加容量选择及原子窗口/缺口查询。
- 缺失玩家默认拒绝，提供显式 `CREATE_DEFAULT`；登录建会话前查权威仓库，原型示例和脚手架显式启用建档。
- L2 回填失败默认不写 L1，分层缓存与 Redis 门面可显式启用本地降级；自定义 envelope 存储必须实现原子 CAS，未覆盖时直接失败。
- PostgreSQL / MongoDB driver 直接保存拒绝版本倒退，数据包装器保留原始 cause；World 迁移统一 entity lane 并保留交接令牌到源释放；排行榜回调移出状态锁，保留提交后异常契约。
- flush 新标脏丢失在当前基线不成立，原有 generation 和条件删除已保护新入口。逐项结论、风险和验证见[复核报告](docs/reports/report-audit-followup-20260928.zh-CN.md)，0.x 行为变化见[迁移说明](docs/migrations/20260928-report-audit-followup.md)。

### 2026-09-28 报告核实与边界修复

- 完成网络、外部系统、安全、数据缓存、Actor/runtime 与游戏域报告的逐项核查；修复 TCP 默认裸入口、握手限流、UDP peer/超长包、数据库错误分类、持久化 flush、玩家重复登录、world 异步迁移和状态同步摘要校验。
- Redis 本地实现改为显式 prototype 入口，生产注册必须提供真实 `RedisClient`；Nacos/Kafka 保持真实 adapter 和外部依赖 fail-closed 语义；KCP/可靠 UDP/分布式 replay 等未实现能力继续显式 fail-fast。
- 0.x API/默认行为变化与迁移步骤见[迁移说明](docs/migrations/20260928-report-audit-remediation.md)，逐条真实性、证据和剩余风险见任务审计矩阵。

### 2026-09-27 协议工具通用性与工程化升级

- 统一四端 signed byte/short 与非负 int/long 边界，拒绝截断和超界对象；Godot 读取失败返回 null DTO，并保留可查询错误状态。
- `.si` 工程支持跨文件类型引用、来源诊断和方法 `@id(...)`；JSON 项目配置可在 CLI/GUI 共用，新增 validate/plan/check/prune/recover 及机器报告。
- 生成物使用摘要归属清单、文件锁与可恢复写入；显式清理仅删除未手改的过期产物，BOImp 始终保留。统一源码 LF，减少重复生成写入。
- 验证 Java 21、C# 8 / .NET Standard 2.1、.NET 8、TS ES2020 / Node / Chrome、Godot；补充两平台 CI。优化 GD 对象回填复制、C# 回填临时分配与 TS UTF-8 编码器复用。迁移及实测证据见[说明](docs/migrations/20260927-codegen-generalization.md)。

### 2026-09-26 协议生成工具多端升级

- Java、C#、TypeScript、GDScript 生成改为统一渲染与输出预检；跨语言路径冲突、非生成文件和错误目录在写盘前失败，BOImp 与同内容文件继续保留。
- GUI 的语言专用目录留空时跟随总输出目录，C# 默认命名空间与 CLI 一致；修复 Godot 嵌套 codec 作用域和类型推断错误，须重新生成 GDScript 文件。
- 增加四端实际编译、运行和固定协议字节向量冒烟测试。线格式未变，步骤与故障边界见[迁移说明](docs/migrations/20260926-codegen-upgrade.md)。

### 2026-09-26 数据安全、缓存与数据库兼容专项审查

- 修复 Redis 数据与缓存 namespace/collection/cacheName 中的 hash-tag 花括号和冒号分隔边界，避免改变 Redis Cluster 的 key slot 语义或产生跨空间 key 别名。
- 收紧本地数据 journal 路径片段校验，拒绝控制字符、Windows ADS 冒号、点段和尾随点空格等特殊文件名语义。
- 仓库注册表改为并发安全的首次注册语义，重复名称显式失败，不再静默替换运行中的仓库实例。
- 修复内存仓库和 envelope 仓库分页结束位置的整数溢出；不改变 Repository API、信封格式或数据库 schema。
- 完成 MongoDB、Redis、PostgreSQL 单实例兼容与落库场景审查；性能结论、未覆盖的集群/长稳边界和迁移步骤见[专项迁移说明](docs/migrations/20260926-data-cache-db-audit-20260926.md)及[审查报告](docs/reports/data-cache-db-audit-20260926.zh-CN.md)。

### 2026-09-26 线程、缓存与分布式一致性专项审查

- 修复 Actor lane 调度、运行时清理、事件完成、帧推进、网络生命周期和 World/Player/Room/NPC 状态边界中的可复现并发缺陷；异步完成、取消、关闭和旧版本状态不会再遗留不可回收队列或覆盖新状态。
- 修复分层缓存迟到回填复活已失效条目、加载 action stage 未传播取消、版本化失效与突变并发覆盖问题；新增 key 代际和确定性竞态回归测试。
- 修复 Redis 条件写/删除、驱动 envelope、持久化失败审计以及单实例外部验证路径；Redis 外部测试使用专用本地实例并保持失败显式。
- 修复 RPC 调用方取消未终止 transport response、pending request 清理、服务发现轮询和 Kafka/Nacos 外部 Actor 测试认证边界；默认外部组件仍 fail-closed。
- 修复 Production runtime 资源登记、关闭和外部 Adapter 失败回收路径，保持显式 provider 选择及失败不回退语义。
- 完整验证、迁移步骤和剩余风险见[线程、缓存与分布式一致性专项迁移说明](docs/migrations/20260926-concurrency-distributed-audit-20260926.md)及[审查报告](docs/reports/concurrency-distributed-audit-20260926.zh-CN.md)。

### 2026-09-26 质量基线与 Production 装配修复

- 恢复 Production Starter 的显式 Adapter 选择，修复全局禁止 LOCAL 连唯一的持久化管理器也拒绝、导致默认及混合装配失败的回归。未启用的 Adapter 可使用本地组件；显式启用的外部组件仍必须通过配置、创建和启动健康检查，失败不得回退。
- 补充外部组件缺健康声明、创建失败、启动健康失败的无回退及资源回收验证，默认 Starter 测试覆盖实际启动。
- 校正 README 中已过期的 net 生成编译限制，并注明历史审计候选及 production 策略的后续纠正。验证、迁移和剩余边界见[迁移说明](docs/migrations/20260926-quality-baseline.md)。

### 2026-09-24 代码审计核实与边界修复

- 修复 Redis 条件写跨槽脚本和失败本地 journal 误记录；world 状态、房间事件回滚、缓存失效和帧推进改为失败可重试的提交顺序。
- GM 审批默认 fail-closed，REST 幂等键贯通请求，失败幂等状态终止重试；本次 production 全局禁止 data/cache/RPC 的 LOCAL provider 策略已在 9 月 26 日纠正，见上方记录。
- 修复同步 RPC 本地超时取消、TLS 握手启动时序、完整帧 replay 摘要、UDP 帧长边界、HTTP 重复头覆盖、状态同步同版本回退、IP 字面量解析、稳定 UID 和场景进入/移动契约。
- NPC 重复 spawn 与 tick 旧快照覆盖被拒绝；重连时间回绕被阻止。报告误报和未改动设计边界记录于[迁移说明](docs/migrations/20260924-code-audit-20260924.md)及任务验证记录。

### 2026-09-23 依赖升级与验收修复

- 合入已通过完整本机门禁的 MongoDB Driver 5.9.2、Netty 4.2.17.Final、Nacos Client 3.2.3、Jedis 8.0.0，以及 Actions setup-java v6 / upload-artifact v7。
- SpotBugs Maven Plugin 4.10.3.0 因 zero-protocol 的 11 个 `UNS_UNSAFE_CALL` 质量告警保留 4.9.3.0；未使用 suppression 绕过门禁。
- 修复 net 脚手架装配参数、网络开关和 runtime 模板依赖闭包；默认拒绝握手，保留框架有界限流。验收入口补齐依赖构建与跨平台 classpath，普通 CI 明确采集本地范围证据，Stage 0 独立执行。
- 修复零延迟 scheduler 回调在 future 返回前重排导致的周期任务停摆；按登记身份管理回调和取消，保留原有调度与预算语义。
- 修复快速网络 bind 忽略已设置中断的竞态，保留启动失败错误码、listener 回收及借用 IO 组所有权。
- 调度器测试增加有界线程退出等待以消除关闭尾部竞态；独立调度示例使用显式异步完成信号，消除依赖固定 sleep 的验收误报。完整验证、外部 Docker 限制和回滚方式见[迁移说明](docs/migrations/20260923-dependency-upgrades.md)。

### 2026-09-23 公开检出 CI 入口

- 能力台账与发布材料检查使用已跟踪的公开证据，移除对维护者私有脚本、任务档案和本机规则的依赖，仍严格拒绝必需材料缺失。
- 平台事务测试先构建 reactor 依赖，再执行原有指定测试；修复 Windows 子进程 Path 键大小写导致的工具查找失败。验证与边界见[迁移说明](docs/migrations/20260923-public-ci-gates.md)。

### 2026-09-23 第三轮性能与 IO 资源治理

- 单帧出站省去批次包装并统一写/flush 完成屏障，修复同步写成功后漏报 flush 失败；AOI 少量变化减少临时集合。
- EventBus 同步成功使用可隔离转换的只读 CompletionStage，默认 Actor ID 保留字符串格式并减少构造中间表示。
- TCP/HTTP/UDP 统一通过 NettyIoResources 管理 IO 组；可选 runtime 装配支持资源登记、共享拥有权、回滚和独立连接回收。
- 绑定失败统一为 START_FAILED 并保留底层 cause。业务接入、可选资源拥有权及验证见[迁移说明](docs/migrations/20260923-performance-third.md)。
- 真实生成 DTO、GC/flush/TLS/建连参数对照及 2h 长稳证据见[第三轮报告](docs/reports/performance-third-20260923.zh-CN.md)；保留未采用候选和资源趋势边界，不调整部署默认值。
- 合并前审查修复负载驱动在最后响应移出在途表后、完成计数发布前提前汇总的竞态；最终结果等待计数结算，超时明确失败。历史样本保持原测量身份。

### 2026-09-23 codegen 对接修复

- Java 生成分发器的帧和数组入口统一使用只读 reader，在执行 BO 前拒绝对象外尾随数据；保持 DSL、线格式和业务签名。
- BOImp 仅首次创建，重生成保留已有业务代码；四语言生成文件内容不变时不重写，减少无效构建。
- 同步 CLI/GUI 提示与网络接入指南；新增真实生成代码编译/执行、分模块布局和重生成保护回归。0.x 行为变化与验证见 [迁移说明](docs/migrations/20260923-codegen-integration.md)。

### 2026-09-23 性能增量

- EventBus 使用同版本注册快照与同步迭代完成路径；AOI 合并观察状态、复用有界候选工作区并跳过无变化观察。
- 只读 payload 到 reader/生成 Dispatcher 贯通；自定义 codec 保留默认数组适配，ProtocolFrame 继续自持有且保持 record 表示。
- 排行榜复用同版本有界 TopN；保留原同步通知及全局锁。direct/Netty 优化重叠搬移。
- 业务接入、只读借用边界、生成入口及验证见[增量迁移说明](docs/migrations/20260923-performance-incremental.md)。

### 2026-09-23 性能方案 S0-S3

- 排名使用跨度跳表；缓存统计采用 LongAdder，Scene 私有状态由 Lane 独占；Actor 注册快照缓存派生类型解析，内部消息使用低成本关联 ID。
- Actor 增加每 Lane/全局准入、批次公平调度、关闭与固定维度队列观测；Local 和 Executor 共享调度语义。
- 协议提供无复制长度/只读视图及缓冲 codec 入口；Netty 直接缓冲编解码、同连接批写、显式水位/出站预算以及 NIO/AUTO/EPOLL 配置。
- AOI 增量维护观察快照并提供观察者释放；帧同步维护确定顺序并限制参与者历史。
- 0.x 默认行为、错误码、API 和验证边界见[迁移说明](docs/migrations/20260923-performance-plan.md)。性能报告同时列出排名写入和 Actor 显式 ID 路径的成本，不将吞吐倒数当作请求延迟。

### 2026-09-22 调度、AOI 与编码分配优化

- `ExecutorActorScheduler` 使用 64 个锁段和有序处理器快照，空闲 lane 回收与拒绝清理在所属段内完成；修复旧注销句柄误删同对象新注册及 direct executor 完成竞态递归。
- `InMemoryAoiIndex` 使用二维均匀网格，支持指定网格边长；保留精确视野、事件顺序和状态序号，超大范围只扫描已占用格。
- 默认编码路径有界复用临时堆缓冲；`ZeroPayloadCodec.write` 的 writer/视图仅可在同步调用内借用。direct 批量读写减少包装分配，native 原始地址操作补充 Cleaner 可达性保障。
- Java 21 基线不启用预览；相关源码注明 FFM 在 Java 21 为预览、Java 22 正式定版及后续迁移约束。迁移与验证见[说明](docs/migrations/20260922-performance-feedback.md)。

### 2026-09-22 安全、持久化与异步确认修复

- HTTP 元数据经应用验证后按请求传播身份；Kafka 默认拒绝未验证身份，runtime 支持显式注入 verifier。
- Kafka 关闭自动提交，异步业务及响应发送完成后提交批次；默认从 earliest 消费，默认消费组按实例隔离。
- Redis 条件写失败明确返回异常；删除快照、版本、索引和日志原子执行；本地日志强制刷盘并恢复完整记录边界。
- 缓存条件失效保留更新条目、同实体版本拒绝覆盖；容量淘汰有序且同步；单飞加载有超时和取消释放。
- Prometheus 接收外部异步执行器，支持 Bearer token，禁止无 token 的非回环绑定。
- 逐项结论、0.x API/默认行为变化及验证见[迁移说明](docs/migrations/20260922-security-storage-concurrency.md)。Actor、事件总线和 UDP 的原始缺陷描述不成立，保留实现并补充验证。

### 2026-09-18 文档整理

- 对照当前实现更新 TCP/脚手架入口、能力矩阵与路线图，补齐迁移和报告导航；修复公开文档对本机任务档案的引用。范围与验证口径见[整理说明](docs/migrations/20260918-documentation-audit.md)。

### 2026-09-17 报告核实修复

- 修复缓存单飞残留与旧值回填、停机脏对象未保存、帧输入拒绝/计数、房间资源回收、状态同步回退、排行榜溢出及 AOI 更新/离开标识问题。
- 事件处理器失败后继续派发，最终汇总错误；内存死信、指标与房间事件历史增加容量和淘汰统计。
- 协议声明长度分配前校验；Java 包名/DTO 后缀与输出路径校验；RPC 统一超时预算、时间轮选桶重验及 Kafka 尾随字节拒绝。
- 新增相邻路径回归验证。0.x 行为/API 调整和验证证据见 [迁移说明](docs/migrations/20260917-bug-report-verification.md) 与 [逐项核实报告](docs/reports/bug-analysis-verification-20260917.zh-CN.md)。

### Added

- local TCP scaffold 的业务流程模板现在只接收 `LogAppender`，并通过组合根传入已验证的日志适配端口；不再让生成的业务侧代码直接持有 terminal `LogSink`。架构守卫与 7 个本地模板的生成、测试和运行验证均通过。

- P0-1 local scaffold now supports an explicit `net` component that generates a long-running TCP server/client pair, owns runtime/listener/service cleanup, and has a verified local loopback request/response smoke. The boundary remains prototype-only: authentication, TLS, heartbeat, rate limiting, capacity and long-stability evidence are not included.
- 脚手架事务新增工程级独占升级锁、原子 state/LATEST 指针写入和新增受控文件 rollback 清理；Windows 本地 focused evidence 已覆盖锁竞争与恢复，Linux/macOS 强杀和文件系统差异仍需 runner 证据。
- 脚手架新增安全升级操作 `--plan`/`--diff`/`--apply`/`--migrate`/`--rollback`/`--abort`：基于 ownership hash 做三路比较，用户修改的 generated 文件冲突即拒绝覆盖；apply 使用 `.zero/scaffold/transactions` 快照和原子替换，失败可恢复并回滚。旧 manifest 必须显式 migrate，`--force` 不再绕过冲突。详见 [脚手架 ownership 迁移说明](docs/migrations/20260914-scaffold-ownership-manifest.md)。
- 场景接入指南、可运行的最小中心—逻辑接口示例；runtime 脚手架新增 Kafka、Nacos、MongoDB、PostgreSQL 组件选择，自动补齐所需依赖并替换相应本地实现。
- `ProductionAssembly.plan()` 提供无资源的实际组件图；新增显式档位 builder overload，补齐 standalone 装配，生成工程可通过配置切换档位。

- P0-4 GM 生产运营边界扩展：新增有界审计查询/留存/归档契约、业务幂等 claim/conflict/TTL、break-glass 一次性授权边界、解析无关安全失败载体和 GM RPC transport-neutral boundary；内存实现仅用于 local/test，不声称真实 HTTP/Kafka/数据库运营闭环。
- 新增 `ZeroUnifiedEntryVerifier`，验证两端命令契约、脚本安全状态管理和 POSIX stop 幂等语义。
- 阶段 0 验收脚本的进程输出采集和超时进程树处理兼容 Java 17 进行预检编译；项目实际构建和运行仍明确要求 Java 21。
- 阶段 1 模块化运行时装配设计与 `zero-runtime` 1B/1C 通用契约：显式 catalog/selection、typed config、确定性依赖图、双资源账本、启动健康、single-use 生命周期、稳定错误码、安全诊断和共享能力模型；Local Starter、生成器、示例及模板已迁移，真实 Adapter provider 留待 1D。
- 1D-0 Production 迁移基础契约：`GameRuntime.optional(...)`、相互独立的 assembly/startup deadline，以及 `standalone`、`external-test`、`production` profile 和中立 data/discovery/resolver/network capability 词汇。
- 1D-1 Kafka RPC 正式 runtime provider：稳定 provider ID、typed startup schema、显式日志依赖、双 RPC capability、mandatory startup health，以及保持不变的安全属性白名单和延迟连接边界。
- 1D-2 MongoDB data 正式 runtime provider：稳定 provider ID、敏感 typed schema、`DataService` 多值贡献、mandatory startup health，以及立即登记到中立 build resource ledger 的 Mongo client。
- 1D-3 Redis provider family：包内共享资源句柄、独立 data/cache provider、单一中立 ledger client、`DataService`/`CacheService` 业务能力和各自 mandatory startup health；未公开 `RedisClient` typed capability。
- 1D-4/5 PostgreSQL 与 Nacos provider：敏感 typed schema、中立 data/discovery/resolver capability、mandatory startup health 和既有启动预算语义。
- 1D-6 production network provider：显式 policy、10 项非敏感 typed config、受管非内联 remote IO executor 和默认/自定义有界限流器。
- 1D-7 收敛：`ZeroProductionRuntime` 直接实现 `GameRuntime`，删除驱动 getter、package-private bridge、重复 resource scope 和旧 network factory。
- 首次公开 GitHub 仓库、完整项目首页、贡献指南、安全策略、行为准则、Issue/PR 模板和 Dependabot 配置。
- Java 21 GitHub Actions，覆盖默认测试、Checkstyle、PMD、SpotBugs、JaCoCo、示例和脚手架验证。
- 独立 `zero-benchmarks` JMH 模块以及 Zero Binary Protocol、Protobuf、FlatBuffers 的可复现横向基准。

- 新增显式 `ZeroServerTcpApplication` starter 生命周期门面，支持注入 listener 的 `start`/`probe`/`stop`、端口冲突补偿清理和重复停止幂等语义；脚手架可显式选择 `net` 组件并生成网络策略依赖。本地 TCP evidence 不代表 productionReady。

- 整理文档导航和职责：合并重复接入/API/容量说明，按指南、参考、运维和历史报告归类；修正 GM、监控、世界分片和脚手架状态。目录调整与旧链接迁移见[文档路径说明](docs/migrations/20260914-documentation-layout.md)，不改变运行时 API 或配置。

- runtime 外部 smoke 改为仅诊断，连接地址不写死进业务源码；缺失配置明确输出 incomplete，多来源 Repository 按来源名绑定。详见 [0.x 迁移说明](docs/migrations/20260914-scenario-composition.md)。
- 修复组件间装配超时跳过回滚、健康探针超出累计预算仍进入 RUNNING、诊断冻结集合选择 Builder 三项运行时 bug；均有修复前失败的确定性回归测试。
- 真实 TCP 示例使用框架统一管理的执行器，协议 codegen 移入构建插件依赖；场景消费者验证所选 SDK 与实际 provider 图，完整治理状态保持未完成。

- 修复 GitHub Actions 工作流的 `jobs` 顶层结构，恢复 compatibility、unit、quality、integration、Stage 0 和跨平台入口任务的正常解析。
- 修正 production network provider 对 `SecurityChain.tlsRequired()` 的配置传播，避免安全链要求 TLS 时被网络配置覆盖。
- 将 GM 幂等操作指纹改为带明确分隔符的 SHA-256 摘要，降低短整数 hash 碰撞导致错误复用的风险。
- 同步架构守卫与缺口台账文档中的当前模块数和能力状态；这些同步不改变 `productionReady=false` 边界。

- `zero-runtime` 在 1B 复审中收紧 callback 异常归一化、确定性 catalog/selection 冻结和 startup health 超时取消；公开异常与报告不携带 raw cause 或配置值。
- Local/Production Starter 统一复用 `GameRuntime` 生命周期与双 ledger 契约；Production Adapter 配置、健康预算和回滚已由正式 provider 接入同一组件图。
- Production 累计启动预算现在只覆盖 lifecycle start 与 startup health；planning/config/create 使用独立装配预算，不再从资源创建阶段提前消耗启动预算。
- 共享 capability model 现在可声明 provider-specific 依赖与实现制品，使具体实现依赖不会被错误提升为所有 provider 的能力依赖，生成器也能计算完整 provider artifact 闭包。
- 公开文档改为面向使用者、部署者和贡献者组织，移除内部任务、计划和审计材料。
- Maven 项目元数据、SCM 和 Issue 地址更新为 `yixunfei/zero-server`。

## 0.1.0-SNAPSHOT — Development Preview

### Runtime kernel

- Java 21 Maven 多模块工程、统一 Parent/BOM 和生命周期抽象。
- ErrorCode 分类、统一异常、配置快照、SPI 排序与基础执行域边界。
- 本地确定性 Actor 调度、lane 绑定、事件总线、拦截器、重试/死信基础能力和 TraceId 上下文。
- 受管定时任务契约与本地实现，支持 once、fixed-delay、fixed-rate、取消、限流、失败策略和观察器。

### Protocol and code generation

- Zero Binary Protocol buffer、Frame Codec、payload codec、注册表和 nullable/集合编码。
- `.si` 协议 DSL 解析、校验和协议 ID 管理。
- 生成 Java DTO/Codec/EventBO/默认实现/Dispatcher/ErrorCode/测试，以及 C#、TypeScript、GDScript 客户端协议代码和文档。

### Game model

- 游戏业务执行域、请求上下文和 Actor 投递网关。
- 玩家登录、会话、在线数据加载、Repository/Cache 协作和查询基础 API。
- 场景进入、移动、离开、实体坐标和查询基础 API。
- RPG、房间、场景同步、帧同步、NPC Tick、排行榜赛季、开放世界分片脚手架。

### Networking and RPC

- Netty TCP、UDP、HTTP 最小服务器和协议帧桥接。
- 显式启用的生产 TCP 生命周期最小实现：状态机、握手、异步鉴权端口、心跳、入站预算、限流、重连协调和遥测观察器。
- RPC request/response、oneway、broadcast 抽象和公共接口代理。
- Kafka RPC Adapter、pending request、超时轮、reply topic、correlationId、traceId、timeoutAt 与安全资源回收。
- Nacos 服务发现 Adapter 和 RPC 实例解析。

### Data and cache

- 统一 `Repository` / `DataService`、对象映射、数据 envelope 和版本冲突基础能力。
- MongoDB、Redis、PostgreSQL Adapter。
- 本地缓存、分层缓存、Redis L2、自动加载、写回/失效、版本和防击穿/防穿透基础能力。
- Redis 追加式数据日志和本地磁盘过渡实现。

### Operations

- CSV 配置加载与本地原子热重载，支持失败保旧、校验、哈希去重、WatchService 和审计观察器。
- GM 指令 DSL、注册、dry-run、执行编排、审计归因、业务提交状态和统一 ErrorCode。
- 统一结构化日志、敏感字段拒绝/脱敏、处理管线、System.Logger 和监控告警 Sink。
- 内存指标注册表、低基数标签约束、Prometheus 文本导出、Grafana JSON、告警规则和系统指标采集。
- 本地无 Docker Starter 与显式 Production Starter；真实 Adapter 默认不进入本地装配。

### Examples and developer experience

- RPG 本地业务闭环和协议驱动示例。
- 真实 TCP generated dispatcher 示例。
- CSV 配置热重载、受管定时任务和可观测性示例。
- 关键词驱动的项目脚手架生成、运行、结构检查和批量验证工具。

### Known limitations

- 当前不是生产就绪版本，不提供容量、长稳、p99 或 SLA 承诺。
- 生产 TCP 生命周期不包含完整 TLS/WAF/DDoS、真实账号鉴权和完整网关。
- GM 尚未提供完整 RBAC、IP 白名单和审批服务。
- 生产文件/Kafka 日志 Sink、Prometheus HTTP Endpoint 和完整告警推送仍待实现。
- CGLIB 修复、ClassLoader 活动插件和集群热更同步仍处于设计边界。
- 外部组件默认测试不会自动启动 Docker，需要部署方显式提供环境并启用 `external-tests`。
