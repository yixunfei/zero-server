import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * zeroServer 最终目标功能缺失台账。
 *
 * <p>该工具围绕“开箱即用、需求驱动、模块化组件化、多类型通用游戏服务器开发框架”的目标，
 * 汇总当前能力状态、证据路径、剩余缺口和下一步命令。它只读取文件，不创建模块、不运行测试、
 * 不批准高风险实现。</p>
 *
 * @author zn
 */
public final class ZeroFrameworkGapLedger {

    /**
     * 当前目标能力台账。
     */
    private static final List<CapabilityGap> GAPS = List.of(
            new CapabilityGap(
                    "onboarding-loop",
                    "开箱即用上手闭环",
                    GapStatus.READY,
                    "新用户可以在无 Docker 条件下从 quickstart、doctor、onboarding verifier 和示例入口开始。",
                    List.of(
                            evidence("quickstart", Path.of("docs", "quickstart.zh-CN.md"), "ZeroLocalDoctor"),
                            evidence("doctor", Path.of("scripts", "ZeroLocalDoctor.java"), "zero-local-doctor=ok"),
                            evidence("onboarding-verifier", Path.of("scripts", "ZeroOnboardingVerifier.java"),
                                    "zero-onboarding-verifier=ok"),
                            evidence("minimal-example", Path.of("examples", "rpg-minimal", "pom.xml"), ""),
                            evidence("tcp-example", Path.of("examples", "rpg-tcp-generated", "pom.xml"), "")),
                    List.of("不证明生产部署可用。"),
                    List.of("java scripts/ZeroOnboardingVerifier.java", "java scripts/ZeroBuildSmokeVerifier.java"),
                    List.of()),
            new CapabilityGap(
                    "demand-driven-development",
                    "需求驱动开发入口",
                    GapStatus.PROTOTYPE,
                    "用户可以用关键词选择组件栈、生成 local/prototype 项目并读取首改指南。",
                    List.of(
                            evidence("demand-start-sheet", Path.of("scripts", "ZeroDemandStartSheet.java"),
                                    "zero-demand-start-sheet=ok"),
                            evidence("stack-advisor", Path.of("scripts", "ZeroGameStackAdvisor.java"),
                                    "zero-game-stack-advisor=ok"),
                            evidence("new-local-game", Path.of("scripts", "NewLocalGame.java"), "--template"),
                            evidence("local-template", Path.of("templates", "local-game-scaffold",
                                    "zero-scaffold.json.tpl"), "")),
                    List.of("仍是 local/prototype，不冻结正式业务 API。", "正式模块晋升需要单独确认。"),
                    List.of("java scripts/ZeroDemandStartSheet.java --keywords \"open world shard\"",
                            "java scripts/ZeroGameStackAdvisor.java --recipe \"room ranking world\""),
                    List.of("java scripts/ZeroFrameworkReadiness.java --confirmGap room-matchmaking")),
            new CapabilityGap(
                    "modular-architecture-index",
                    "模块化组件化导航",
                    GapStatus.PROTOTYPE,
                    "用户可以从模块目录、能力矩阵、架构守卫和 module-map 理解模块边界。",
                    List.of(
                            evidence("module-catalog", Path.of("scripts", "ZeroModuleCatalog.java"),
                                    "zero-module-catalog=ok"),
                            evidence("capability-matrix", Path.of("docs", "capability-matrix.zh-CN.md"),
                                    "zero-runtime"),
                            evidence("architecture-guard", Path.of("scripts", "ZeroArchitectureGuard.java"),
                                    "zero-architecture-guard=ok"),
                            evidence("module-map", Path.of("docs", "module-map.md"), "zero-core")),
                    List.of("不代表依赖方向已经适合所有正式能力。", "模块结构变化仍必须同步 docs/module-map.md。"),
                    List.of("java scripts/ZeroModuleCatalog.java", "java scripts/ZeroArchitectureGuard.java"),
                    List.of()),
            new CapabilityGap(
                    "multi-type-local-prototypes",
                    "多类型本地原型模板",
                    GapStatus.PROTOTYPE,
                    "local、room、scene-sync、frame-sync、npc-tick、ranking-season、world-shard 七类模板齐备。",
                    List.of(
                            evidence("scaffold-catalog", Path.of("docs", "scaffold-templates.zh-CN.md"),
                                    "world-shard"),
                            evidence("verify-scaffolds", Path.of("scripts", "VerifyLocalScaffolds.java"),
                                    "TEMPLATE_SPECS"),
                            evidence("room-template", Path.of("templates", "room-game-scaffold",
                                    "zero-scaffold.json.tpl"), ""),
                            evidence("world-template", Path.of("templates", "world-shard-scaffold",
                                    "zero-scaffold.json.tpl"), "")),
                    List.of("模板不是正式 gameplay 组件。", "room / AOI / frame / NPC / ranking / world 的本地最小模块已存在，但仍不是生产玩法模块。"),
                    List.of("java scripts/NewLocalGame.java --listTemplates",
                            "java scripts/VerifyLocalScaffolds.java --skipInstall --skipRun"),
                    List.of("java scripts/ZeroImplementationSliceSelector.java --confirmSlice room-component-minimum-contract")),
            new CapabilityGap(
                    "protocol-driven-business-flow",
                    "协议驱动业务闭环",
                    GapStatus.LIMITED,
                    "协议 DSL 已能生成 DTO、codec、BO、dispatcher，并在示例中跑通业务首改路径。",
                    List.of(
                            evidence("protocol-dsl-doc", Path.of("docs", "protocol-dsl.zh-CN.md"), "XXXEventBO"),
                            evidence("codegen-module", Path.of("zero-codegen", "pom.xml"), ""),
                            evidence("rpg-protocol", Path.of("examples", "rpg-minimal", "src", "main",
                                    "protocol", "Rpg.si"), ""),
                            evidence("tcp-generated", Path.of("examples", "rpg-tcp-generated", "src", "main",
                                    "protocol", "RpgTcp.si"), "")),
                    List.of("正式 Maven plugin、发布模板和跨语言生成稳定契约仍需收口。", "协议 DSL / codegen 规则属于高风险公共契约。"),
                    List.of("java scripts/ZeroDemandStartSheet.java --stack local-rpg"),
                    List.of("java scripts/ZeroFrameworkReadiness.java --confirmGap performance-baseline")),
            new CapabilityGap(
                    "production-safety-foundation",
                    "生产安全基础",
                    GapStatus.LIMITED,
                    "生产网络生命周期、可观测性与 PAF1 启动期 fail-fast 三个最小切片已实现；完整 Adapter hardening 和 GM 安全运营仍待后续确认。",
                    List.of(
                            evidence("production-safety", Path.of("docs", "capability-matrix.zh-CN.md"),
                                    "productionReady=false"),
                            evidence("network-archive", Path.of("tasks", "archive",
                                    "20260727-production-network-lifecycle-minimum-slice", "VERIFY.md"),
                                    "minimum-slice-implemented"),
                            evidence("network-runtime", Path.of("zero-net", "src", "main", "java",
                                    "group", "zn", "zero", "net", "lifecycle",
                                    "ProductionNetworkLifecycle.java"),
                                    "public final class ProductionNetworkLifecycle"),
                            evidence("network-focused-tests", Path.of("zero-net", "src", "test", "java",
                                    "group", "zn", "zero", "net", "netty",
                                    "ProductionNetworkLifecycleFocusedTest.java"), "PNFT-10"),
                            evidence("network-contract", Path.of("docs", "reference", "production-network-lifecycle-contract.zh-CN.md"),
                                    "production-network-lifecycle-contract"),
                            evidence("observability-contract", Path.of("docs", "reference", "observability-minimum-field-contract.zh-CN.md"),
                                    "observability-minimum-field-runtime"),
                            evidence("observability-archive", Path.of("tasks", "archive",
                                    "20260727-observability-minimum-field-runtime", "VERIFY.md"),
                                    "minimum-slice-implemented"),
                            evidence("observability-runtime", Path.of("zero-log", "src", "main", "java",
                                    "group", "zn", "zero", "log", "LogAppender.java"),
                                    "public interface LogAppender"),
                            evidence("observability-focused-example", Path.of("examples", "observability-local",
                                    "src", "test", "java", "group", "zn", "zero", "examples",
                                    "observability", "ObservabilityLocalApplicationTest.java"), "OFRT-12"),
                            evidence("gm-contract", Path.of("docs", "reference", "gm-operation-context-contract.zh-CN.md"),
                                    "minimum-slice-implemented"),
                            evidence("gm-authorizer", Path.of("zero-gm", "src", "main", "java", "group", "zn", "zero", "gm",
                                    "GmOperationAuthorizer.java"), "GmSourceIpPolicy"),
                            evidence("gm-focused-tests", Path.of("zero-gm", "src", "test", "java", "group", "zn", "zero", "gm",
                                    "GmOperationAuthorizerTest.java"), "rejectsSourceBeforeApprovalAndExecution"),
                            evidence("gm-standard-entry", Path.of("zero-gm", "src", "main", "java", "group", "zn", "zero", "gm",
                                    "GmOperationEndpoint.java"), "GmOperationResponse"),
                            evidence("gm-persistent-audit", Path.of("zero-gm", "src", "main", "java", "group", "zn", "zero", "gm",
                                    "PersistentGmAuditHook.java"), "GmAuditStore"),
                            evidence("gm-record-store", Path.of("zero-gm", "src", "main", "java", "group", "zn", "zero", "gm",
                                    "GmAuditRecordStore.java"), "append"),
                            evidence("gm-transport-boundary", Path.of("zero-gm", "src", "main", "java", "group", "zn", "zero", "gm",
                                    "GmTransportAdapter.java"), "requireAuthenticated")),
                    List.of("网络最小切片不证明真实 token 鉴权、TLS、WAF / DDoS、完整网关、多传输、容量或长稳。",
                            "可观测性最小切片不包含生产文件 / Kafka sink、Prometheus HTTP endpoint、远程导出、容量或长稳。",
                            "PAF1 已实现启动期最小边界；周期健康、自动恢复、故障转移、完整重平衡、容量、长稳、生产身份认证、持久 RBAC/IP 白名单、审批引擎和完整 GM 安全闭环仍未实现。"),
                    List.of("java scripts/ZeroProductionSafetyPacket.java",
                            "java scripts/ZeroImplementationSliceSelector.java --describeSlice gm-operation-context-contract"),
                    List.of("java scripts/ZeroImplementationSliceSelector.java --confirmSlice gm-operation-context-contract")),
            new CapabilityGap(
                    "gameplay-component-formalization",
                    "正式玩法组件化",
                    GapStatus.LIMITED,
                    "zero-room 与 AOI / 状态同步、frame-sync 已形成本地正式最小切片；NPC、ranking、world-shard 仍只有模板或契约草案。",
                    List.of(
                            evidence("room-contract", Path.of("docs", "reference", "room-component-minimum-contract.zh-CN.md"),
                                    "room-component-minimum-contract"),
                            evidence("zero-room-module", Path.of("zero-room", "pom.xml"),
                                    "<artifactId>zero-room</artifactId>"),
                            evidence("zero-room-runtime", Path.of("zero-room", "src", "main", "java", "group", "zn", "zero", "room",
                                    "LocalRoomService.java"), "All mutations are dispatched on the room lane"),
                            evidence("zero-room-focused-tests", Path.of("zero-room", "src", "test", "java", "group", "zn", "zero", "room",
                                    "LocalRoomServiceTest.java"), "capacityAndDuplicateJoinAreSafe"),
                            evidence("room-game-example", Path.of("target", "scaffold-demand-regression", "verify-room-game", "README.md"),
                                    "room-game=ok|mode=local|name=verify-room-game"),
                            evidence("room-capacity-marker", Path.of("zero-room", "src", "main", "java", "group", "zn", "zero", "room",
                                    "LocalRoomService.java"), "capacity"),
                            evidence("aoi-runtime-module", Path.of("zero-aoi", "pom.xml"), "<artifactId>zero-aoi</artifactId>"),
                            evidence("aoi-runtime", Path.of("zero-aoi", "src", "main", "java", "group", "zn", "zero", "aoi",
                                    "InMemoryAoiIndex.java"), "Deterministic, single-owner in-memory AOI implementation"),
                            evidence("aoi-focused-tests", Path.of("zero-aoi", "src", "test", "java", "group", "zn", "zero", "aoi",
                                    "InMemoryAoiIndexTest.java"), "visibilityUsesChebyshevAndSequences"),
                            evidence("aoi-capacity-marker", Path.of("examples", "aoi-state-sync", "src", "main", "java", "group", "zn", "zero", "examples",
                                    "aoisync", "AoiStateSyncDemo.java"), "maxEntities"),
                            evidence("state-sync-runtime-module", Path.of("zero-state-sync", "pom.xml"), "<artifactId>zero-state-sync</artifactId>"),
                            evidence("state-sync-runtime", Path.of("zero-state-sync", "src", "main", "java", "group", "zn", "zero", "statesync",
                                    "InMemoryStateSync.java"), "explicit resync result"),
                            evidence("state-sync-focused-tests", Path.of("examples", "aoi-state-sync", "src", "test", "java", "group", "zn", "zero", "examples",
                                    "aoisync", "AoiStateSyncDemoTest.java"), "snapshotAndBaselineMismatchAreExplicit"),
                            evidence("aoi-state-sync-example", Path.of("examples", "aoi-state-sync", "src", "main", "java", "group", "zn", "zero", "examples",
                                    "aoisync", "AoiStateSyncDemo.java"), "aoi-state-sync=ok|mode=local"),
                            evidence("aoi-contract", Path.of("docs", "reference", "aoi-state-sync-minimum-contract.zh-CN.md"),
                                    "aoi-state-sync-minimum-contract"),
                            evidence("frame-contract", Path.of("docs", "reference", "frame-sync-minimum-contract.zh-CN.md"),
                                    "frame-sync-minimum-contract"),
                            evidence("frame-runtime-module", Path.of("zero-frame-sync", "pom.xml"),
                                    "<artifactId>zero-frame-sync</artifactId>"),
                            evidence("frame-runtime", Path.of("zero-frame-sync", "src", "main", "java", "group", "zn", "zero", "framesync", "FrameMatchRuntime.java"),
                                    "Bounded, single-lane frame runtime"),
                            evidence("frame-focused-tests", Path.of("zero-frame-sync", "src", "test", "java", "group", "zn", "zero", "framesync", "FrameMatchRuntimeTest.java"),
                                    "frameClockAndOrderingAreAuthoritative"),
                            evidence("frame-example", Path.of("examples", "frame-sync", "src", "main", "java", "group", "zn", "zero", "examples", "framesync", "FrameSyncDemo.java"),
                                    "frame-sync=ok|mode=local"),
                            evidence("frame-example-tests", Path.of("examples", "frame-sync", "src", "test", "java", "group", "zn", "zero", "examples", "framesync", "FrameSyncDemoTest.java"),
                                    "broadcastOrderFollowsFrameNo"),
                            evidence("frame-capacity", Path.of("examples", "frame-sync", "src", "main", "java", "group", "zn", "zero", "examples", "framesync", "FrameSyncDemo.java"),
                                    "maxPlayers"),
                            evidence("npc-contract", Path.of("docs", "reference", "npc-tick-minimum-contract.zh-CN.md"),
                                    "npc-tick-minimum-contract"),
                            evidence("npc-runtime-module", Path.of("zero-npc", "pom.xml"),
                                    "<artifactId>zero-npc</artifactId>"),
                            evidence("npc-runtime", Path.of("zero-npc", "src", "main", "java", "group", "zn", "zero", "npc", "LocalNpcZone.java"),
                                    "public TickResult tick"),
                            evidence("npc-focused-tests", Path.of("zero-npc", "src", "test", "java", "group", "zn", "zero", "npc", "NpcRuntimeTest.java"),
                                    "tickBudgetLimitsProcessedNpcCount"),
                            evidence("npc-example", Path.of("examples", "npc-tick", "src", "main", "java", "group", "zn", "zero", "examples", "npctick", "NpcTickDemo.java"),
                                    "npc-tick=ok|mode=local|ticks="),
                            evidence("npc-capacity", Path.of("docs", "reference", "npc-tick-minimum-contract.zh-CN.md"),
                                    "maxNpcsPerZone"),
                            evidence("ranking-contract", Path.of("docs", "reference", "ranking-season-minimum-contract.zh-CN.md"),
                                    "ranking-season-minimum-contract"),
                            evidence("ranking-runtime-module", Path.of("zero-ranking", "pom.xml"),
                                    "<artifactId>zero-ranking</artifactId>"),
                            evidence("ranking-runtime", Path.of("zero-ranking", "src", "main", "java", "group", "zn", "zero", "ranking", "RankingService.java"),
                                    "public interface RankingService"),
                            evidence("ranking-focused-tests", Path.of("examples", "ranking-season", "src", "test", "java", "group", "zn", "zero", "examples", "rankingseason", "RankingSeasonApplicationTest.java"),
                                    "demoCompletesAndUsesFormalApi"),
                            evidence("ranking-example", Path.of("examples", "ranking-season", "src", "main", "java", "group", "zn", "zero", "examples", "rankingseason", "RankingSeasonApplication.java"),
                                    "ranking-season=ok|mode=local"),
                            evidence("ranking-example-tests", Path.of("examples", "ranking-season", "src", "test", "java", "group", "zn", "zero", "examples", "rankingseason", "RankingSeasonApplicationTest.java"),
                                    "demoCompletesAndUsesFormalApi"),
                            evidence("ranking-capacity", Path.of("docs", "reference", "ranking-season-minimum-contract.zh-CN.md"),
                                    "productionReady=false"),
                            evidence("world-contract", Path.of("docs", "reference", "world-shard-minimum-contract.zh-CN.md"),
                                    "world-shard-minimum-contract"),
                            evidence("world-runtime-module", Path.of("zero-world", "pom.xml"),
                                    "<artifactId>zero-world</artifactId>"),
                            evidence("world-runtime", Path.of("zero-world", "src", "main", "java", "group", "zn", "zero", "world",
                                    "LocalWorldService.java"), "Deterministic in-memory world slice"),
                            evidence("world-focused-tests", Path.of("zero-world", "src", "test", "java", "group", "zn", "zero", "world",
                                    "LocalWorldServiceTest.java"), "migrationIsIdempotentByMigrationId"),
                            evidence("world-shard-example", Path.of("examples", "world-shard", "src", "main", "java", "group", "zn", "zero", "examples", "worldshard",
                                    "WorldShardApplication.java"), "world-shard=ok|mode=local"),
                            evidence("world-shard-example-tests", Path.of("examples", "world-shard", "src", "test", "java", "group", "zn", "zero", "examples", "worldshard",
                                    "WorldShardApplicationTest.java"), "migrationMarkerIsStable"),
                            evidence("world-capacity", Path.of("docs", "reference", "world-shard-minimum-contract.zh-CN.md"),
                                    "productionReady=false")),
                    List.of("zero-room、排行榜和 AOI 等正式组件仍是本地单进程内存切片；生产持久化、匹配、跨服、容量与长稳未证明。",
                            "公共 API、协议、存储、跨服和生产容量语义仍未完成。",
                            "formal gameplay remains partial; productionReady=false。"),
                    List.of("java scripts/ZeroImplementationSliceSelector.java --describeSlice ranking-season-minimum-contract"),
                    List.of()),
            new CapabilityGap(
                    "performance-evidence",
                    "核心性能证据",
                    GapStatus.PLANNED,
                    "可观测性切片已有 opt-in JMH 方向性证据；七条核心性能 track 仍只有 readiness，正式阈值、容量和 CI 门禁尚未建立。",
                    List.of(
                            evidence("performance-baseline", Path.of("scripts", "ZeroPerformanceBaseline.java"),
                                    "zero-performance-baseline=ok"),
                            evidence("protocol-codec-evidence", Path.of("docs", "operations", "evidence", "protocol-codec-performance-evidence.zh-CN.md"),
                                    "zero-performance-evidence=track|id=protocol-codec|status=readiness|benchmarkComplete=false|productionReady=false"),
                            evidence("protocol-codec-readiness", Path.of("scripts",
                                    "ZeroProtocolCodecBenchmarkReadiness.java"),
                                    "zero-protocol-codec-benchmark-readiness=ok"),
                            evidence("actor-scheduler-evidence", Path.of("docs", "operations", "evidence", "actor-scheduler-performance-evidence.zh-CN.md"),
                                    "zero-performance-evidence=track|id=actor-scheduler|status=readiness|benchmarkComplete=false|productionReady=false"),
                            evidence("actor-scheduler-readiness", Path.of("scripts",
                                    "ZeroActorSchedulerBenchmarkReadiness.java"),
                                    "zero-actor-scheduler-benchmark-readiness=ok"),
                            evidence("net-frame-evidence", Path.of("docs", "operations", "evidence", "net-frame-performance-evidence.zh-CN.md"),
                                    "zero-performance-evidence=track|id=net-frame|status=readiness|benchmarkComplete=false|productionReady=false"),
                            evidence("net-frame-readiness", Path.of("scripts",
                                    "ZeroNetFrameBenchmarkReadiness.java"),
                                    "zero-net-frame-benchmark-readiness=ok"),
                            evidence("rpc-pending-evidence", Path.of("docs", "operations", "evidence", "rpc-pending-performance-evidence.zh-CN.md"),
                                    "zero-performance-evidence=track|id=rpc-pending|status=readiness|benchmarkComplete=false|productionReady=false"),
                            evidence("rpc-pending-readiness", Path.of("scripts",
                                    "ZeroRpcPendingBenchmarkReadiness.java"),
                                    "zero-rpc-pending-benchmark-readiness=ok"),
                            evidence("repository-save-evidence", Path.of("docs", "operations", "evidence", "repository-save-performance-evidence.zh-CN.md"),
                                    "zero-performance-evidence=track|id=repository-save|status=readiness|benchmarkComplete=false|productionReady=false"),
                            evidence("repository-save-readiness", Path.of("scripts",
                                    "ZeroRepositorySaveBenchmarkReadiness.java"),
                                    "zero-repository-save-benchmark-readiness=ok"),
                            evidence("cache-get-or-load-evidence", Path.of("docs", "operations", "evidence", "cache-get-or-load-performance-evidence.zh-CN.md"),
                                    "zero-performance-evidence=track|id=cache-get-or-load|status=readiness|benchmarkComplete=false|productionReady=false"),
                            evidence("cache-get-or-load-readiness", Path.of("scripts",
                                    "ZeroCacheGetOrLoadBenchmarkReadiness.java"),
                                    "zero-cache-get-or-load-benchmark-readiness=ok"),
                            evidence("scene-move-loop-evidence", Path.of("docs", "operations", "evidence", "scene-move-loop-performance-evidence.zh-CN.md"),
                                    "zero-performance-evidence=track|id=scene-move-loop|status=readiness|benchmarkComplete=false|productionReady=false"),
                            evidence("scene-move-loop-readiness", Path.of("scripts",
                                    "ZeroSceneMoveLoopBenchmarkReadiness.java"),
                                    "zero-scene-move-loop-benchmark-readiness=ok")),
                    List.of("不证明核心性能达标。", "observability micro benchmark 不替代七条核心 track、容量、长稳或 SLA 证据。"),
                    List.of("java scripts/ZeroPerformanceBaseline.java --recommendFirstTrack",
                            "java scripts/ZeroProtocolCodecBenchmarkReadiness.java",
                            "java scripts/ZeroActorSchedulerBenchmarkReadiness.java",
                            "java scripts/ZeroNetFrameBenchmarkReadiness.java",
                            "java scripts/ZeroRpcPendingBenchmarkReadiness.java",
                            "java scripts/ZeroRepositorySaveBenchmarkReadiness.java",
                            "java scripts/ZeroCacheGetOrLoadBenchmarkReadiness.java",
                            "java scripts/ZeroSceneMoveLoopBenchmarkReadiness.java"),
                    List.of("java scripts/ZeroPerformanceBaseline.java --confirmTrack performance-baseline")),
            new CapabilityGap(
                    "release-hardening",
                    "发布与长期维护硬化",
                    GapStatus.LIMITED,
                    "开源治理文件、CI / Maven 分层、发布检查单、迁移模板和只读 readiness 已形成局部闭环。",
                    List.of(
                            evidence("changelog", Path.of("CHANGELOG.md"), "Unreleased"),
                            evidence("contributing", Path.of("CONTRIBUTING.md"), ""),
                            evidence("security", Path.of("SECURITY.md"), ""),
                            evidence("code-of-conduct", Path.of("CODE_OF_CONDUCT.md"), ""),
                            evidence("ci", Path.of(".github", "workflows", "ci.yml"),
                                    "mvn -B -ntp -Pquality verify"),
                            evidence("build-smoke", Path.of("scripts", "ZeroBuildSmokeVerifier.java"),
                                    "zero-build-smoke-verifier=ok"),
                            evidence("release-readiness", Path.of("scripts",
                                    "ZeroReleaseHardeningReadiness.java"),
                                    "zero-release-hardening-readiness=ok"),
                            evidence("release-checklist", Path.of("docs", "operations", "release-checklist.zh-CN.md"),
                                    "zero-release-checklist=template|sections=10|scope=true|layeredVerification=true|rollbackRecovery=true|releaseAuthorization=false"),
                            evidence("migration-template", Path.of("docs", "migrations", "template.zh-CN.md"),
                                    "zero-migration-guide-template=template|sections=9|breakingChanges=true|rollback=true|migrationResult=false")),
                    List.of("还缺正式 release gate、覆盖率 / 性能阈值、制品签名与发布、外部依赖矩阵、长期兼容策略和回滚演练。"),
                    List.of("java scripts/ZeroReleaseHardeningReadiness.java",
                            "java scripts/ZeroBuildSmokeVerifier.java",
                            "java scripts/ZeroCapabilityRoadmap.java --describeMilestone m4-release-hardening"),
                    List.of("java scripts/ZeroCapabilityRoadmap.java --confirmMilestone m4-release-hardening")),
            new CapabilityGap(
                    "formal-runtime-implementation",
                    "正式运行时有限实现",
                    GapStatus.LIMITED,
                    "生产网络与可观测性最小运行时已完成；其余生产安全、正式 gameplay、性能门禁和长期运营能力仍需用户确认后小步实现。",
                    List.of(
                            evidence("target-audit", Path.of("scripts", "ZeroFrameworkTargetAudit.java"),
                                    "zero-framework-target-audit=ok"),
                            evidence("slice-selector", Path.of("scripts", "ZeroImplementationSliceSelector.java"),
                                    "zero-implementation-slice-selector=ok"),
                            evidence("network-archive", Path.of("tasks", "archive",
                                    "20260727-production-network-lifecycle-minimum-slice", "VERIFY.md"),
                                    "minimum-slice-implemented"),
                            evidence("network-runtime", Path.of("zero-net", "src", "main", "java",
                                    "group", "zn", "zero", "net", "lifecycle",
                                    "ProductionNetworkLifecycle.java"),
                                    "public final class ProductionNetworkLifecycle"),
                            evidence("network-focused-tests", Path.of("zero-net", "src", "test", "java",
                                    "group", "zn", "zero", "net", "netty",
                                    "ProductionNetworkLifecycleFocusedTest.java"), "PNFT-10"),
                            evidence("observability-archive", Path.of("tasks", "archive",
                                    "20260727-observability-minimum-field-runtime", "VERIFY.md"),
                                    "minimum-slice-implemented"),
                            evidence("observability-runtime", Path.of("zero-log", "src", "main", "java",
                                    "group", "zn", "zero", "log", "LogAppender.java"),
                                    "public interface LogAppender"),
                            evidence("observability-focused-example", Path.of("examples", "observability-local",
                                    "src", "test", "java", "group", "zn", "zero", "examples",
                                    "observability", "ObservabilityLocalApplicationTest.java"), "OFRT-12"),
                            evidence("goal-completion-audit", Path.of("scripts", "ZeroGoalCompletionAudit.java"),
                                    "zero-goal-completion-audit=ok"),
                            evidence("capability-roadmap", Path.of("docs", "optimization-roadmap.zh-CN.md"),
                                    "P0-2")),
                    List.of("后续高风险正式实现仍需独立用户确认。",
                            "三个最小切片不等于完整生产网关、production sink、完整 Adapter hardening、容量或长稳证明。",
                            "旧 S4C-02 仅进入 rebaseline-review，不代表 RBAC、IP 白名单或审批流完成。",
                            "目标不能标记完成。"),
                    List.of("java scripts/ZeroFrameworkTargetAudit.java --describeRequirement formal-runtime-implementation",
                            "java scripts/ZeroGoalCompletionAudit.java",
                            "java scripts/ZeroProductionSafetyPacket.java",
                            "java scripts/ZeroImplementationSliceSelector.java"),
                    List.of("java scripts/ZeroImplementationSliceSelector.java --confirmSlice gm-operation-context-contract")));

    private ZeroFrameworkGapLedger() {
    }

    /**
     * 运行功能缺失台账。
     *
     * @param args 命令行参数。
     * @throws IOException 读取证据文件失败时抛出。
     */
    public static void main(final String[] args) throws IOException {
        if (hasFlag(args, "--help", "-h")) {
            printHelp();
            return;
        }
        if (hasFlag(args, "--listCapabilities", "--list-capabilities")) {
            printCapabilityList();
            return;
        }
        String capabilityId = optionValue(args, "--describeCapability", "--describe-capability");
        if (!capabilityId.isBlank()) {
            printCapabilityDescription(resolveGap(capabilityId));
            return;
        }
        boolean allowMissingEvidence = hasFlag(args, "--allowMissingEvidence", "--allow-missing-evidence");
        LedgerReport report = inspect(allowMissingEvidence);
        printReport(report);
        if (report.hasErrors()) {
            System.exit(1);
        }
    }

    private static LedgerReport inspect(final boolean allowMissingEvidence) throws IOException {
        LedgerReport report = new LedgerReport(allowMissingEvidence);
        boolean rootEvidence = Files.isRegularFile(Path.of("pom.xml"))
                && Files.isRegularFile(Path.of("CONTRIBUTING.md"))
                && Files.isRegularFile(Path.of("docs", "module-map.md"));
        if (rootEvidence) {
            report.pass("repo-root", "Current directory looks like zeroServer repository root and has repository guidance evidence.");
        } else {
            report.fail("repo-root", "Run this command from the zeroServer repository root.");
        }
        for (CapabilityGap gap : GAPS) {
            report.add(gap, inspectEvidence(gap));
        }
        return report;
    }

    private static List<EvidenceResult> inspectEvidence(final CapabilityGap gap) throws IOException {
        List<EvidenceResult> results = new ArrayList<>();
        for (EvidenceCheck evidence : gap.evidence()) {
            boolean exists = Files.isRegularFile(evidence.path());
            boolean markerFound = evidence.marker().isBlank();
            if (exists && !evidence.marker().isBlank()) {
                String text = Files.readString(evidence.path(), StandardCharsets.UTF_8);
                markerFound = text.contains(evidence.marker());
            }
            results.add(new EvidenceResult(evidence, exists, markerFound));
        }
        return results;
    }

    private static void printReport(final LedgerReport report) {
        System.out.println("zeroServer framework gap ledger");
        for (CheckLine line : report.checks()) {
            System.out.println("[" + line.status().label() + "] " + line.name() + " - " + line.message());
        }
        System.out.println();
        System.out.println("Capabilities:");
        for (GapLine line : report.gaps()) {
            CapabilityGap gap = line.gap();
            System.out.println("[CAPABILITY] id=" + gap.id()
                    + "|status=" + gap.status().code()
                    + "|evidence=" + line.passedEvidenceCount() + "/" + line.evidence().size()
                    + "|requiresConfirmation=" + gap.status().requiresConfirmation());
        }
        System.out.println();
        if (report.hasErrors()) {
            for (GapLine gap : report.gaps()) {
                for (EvidenceResult evidence : gap.evidence()) {
                    if (!evidence.passed()) {
                        System.out.println("[EVIDENCE-FAIL] capability=" + gap.gap().id()
                                + "|label=" + evidence.evidence().label()
                                + "|path=" + evidence.evidence().path()
                                + "|exists=" + evidence.exists()
                                + "|markerFound=" + evidence.markerFound());
                    }
                }
            }
            System.out.println("zero-framework-gap-ledger=failed"
                    + "|capabilities=" + GAPS.size()
                    + "|errors=" + report.errorCount()
                    + "|warnings=0");
            return;
        }
        System.out.println("zero-framework-gap-ledger=ok"
                + "|capabilities=" + GAPS.size()
                + "|ready=" + report.countStatus(GapStatus.READY)
                + "|prototype=" + report.countStatus(GapStatus.PROTOTYPE)
                + "|limited=" + report.countStatus(GapStatus.LIMITED)
                + "|draft=" + report.countStatus(GapStatus.DRAFT)
                + "|planned=" + report.countStatus(GapStatus.PLANNED)
                + "|confirmationGated=" + report.countStatus(GapStatus.CONFIRMATION_GATED)
                + "|goalComplete=false"
                + "|requiresConfirmation=true"
                + "|warnings=0");
        System.out.println();
        System.out.println("Next:");
        System.out.println("  java scripts/ZeroFrameworkGapLedger.java --describeCapability formal-runtime-implementation");
        System.out.println("  java scripts/ZeroGoalCompletionAudit.java");
        System.out.println("  java scripts/ZeroProductionSafetyPacket.java");
        System.out.println("  java scripts/ZeroImplementationSliceSelector.java");
        System.out.println("  java scripts/ZeroImplementationSliceSelector.java --confirmSlice gm-operation-context-contract");
        System.out.println();
        System.out.println("Note: gap ledger is read-only and does not prove final goal completion.");
    }

    private static void printCapabilityList() {
        System.out.println("zeroServer framework gap capabilities");
        for (CapabilityGap gap : GAPS) {
            System.out.println("[CAPABILITY] id=" + gap.id()
                    + "|title=" + gap.title()
                    + "|status=" + gap.status().code()
                    + "|requiresConfirmation=" + gap.status().requiresConfirmation());
        }
        System.out.println();
        System.out.println("zero-framework-gap-list=ok|capabilities=" + GAPS.size()
                + "|goalComplete=false|requiresConfirmation=true");
    }

    private static void printCapabilityDescription(final CapabilityGap gap) throws IOException {
        List<EvidenceResult> evidence = inspectEvidence(gap);
        long passed = evidence.stream().filter(EvidenceResult::passed).count();
        System.out.println("zeroServer framework gap capability");
        System.out.println("id: " + gap.id());
        System.out.println("title: " + gap.title());
        System.out.println("status: " + gap.status().code());
        System.out.println("goal: " + gap.goal());
        System.out.println();
        System.out.println("Evidence:");
        for (EvidenceResult result : evidence) {
            System.out.println("- [" + (result.passed() ? "PASS" : "MISS") + "] "
                    + result.evidence().label()
                    + ": " + result.evidence().path()
                    + markerText(result));
        }
        System.out.println();
        System.out.println("Remaining gaps:");
        printItems(gap.remainingGaps());
        System.out.println();
        System.out.println("Low-risk next commands:");
        printItems(gap.lowRiskCommands());
        if (!gap.confirmationCommands().isEmpty()) {
            System.out.println();
            System.out.println("Confirmation-gated commands:");
            printItems(gap.confirmationCommands());
        }
        System.out.println();
        System.out.println("zero-framework-gap-description=ok|capability=" + gap.id()
                + "|status=" + gap.status().code()
                + "|evidence=" + passed + "/" + evidence.size()
                + "|goalComplete=false"
                + "|requiresConfirmation=" + gap.status().requiresConfirmation());
    }

    private static String markerText(final EvidenceResult result) {
        if (result.evidence().marker().isBlank()) {
            return "";
        }
        return "|marker=" + result.evidence().marker()
                + "|markerFound=" + result.markerFound();
    }

    private static void printItems(final List<String> items) {
        if (items.isEmpty()) {
            System.out.println("- None.");
            return;
        }
        for (String item : items) {
            System.out.println("- " + item);
        }
    }

    private static CapabilityGap resolveGap(final String id) {
        for (CapabilityGap gap : GAPS) {
            if (gap.id().equalsIgnoreCase(id)) {
                return gap;
            }
        }
        System.err.println("Unknown framework gap capability: " + id);
        System.err.println("Run `java scripts/ZeroFrameworkGapLedger.java --listCapabilities` to see supported ids.");
        System.exit(1);
        throw new IllegalArgumentException("Unknown framework gap capability: " + id);
    }

    private static EvidenceCheck evidence(final String label, final Path path, final String marker) {
        return new EvidenceCheck(label, path, marker);
    }

    private static boolean hasFlag(final String[] args, final String... flags) {
        for (String arg : args) {
            for (String flag : flags) {
                if (flag.equalsIgnoreCase(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String optionValue(final String[] args, final String... names) {
        for (int index = 0; index < args.length - 1; index++) {
            for (String name : names) {
                if (name.equalsIgnoreCase(args[index])) {
                    return args[index + 1];
                }
            }
        }
        return "";
    }

    private static void printHelp() {
        System.out.println("zeroServer framework gap ledger");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java scripts/ZeroFrameworkGapLedger.java");
        System.out.println("  java scripts/ZeroFrameworkGapLedger.java --listCapabilities");
        System.out.println("  java scripts/ZeroFrameworkGapLedger.java --describeCapability performance-evidence");
        System.out.println("  java scripts/ZeroFrameworkGapLedger.java --help");
        System.out.println();
        System.out.println("This tool is read-only and always treats the final goal as incomplete until all gaps are verified.");
    }

    /**
     * 功能缺口状态。
     */
    private enum GapStatus {

        /**
         * 已满足轻量上手要求。
         */
        READY("ready", false),

        /**
         * 已有原型能力。
         */
        PROTOTYPE("prototype", true),

        /**
         * 有限正式能力或局部闭环。
         */
        LIMITED("limited", true),

        /**
         * 已有正式化前草案。
         */
        DRAFT("draft", true),

        /**
         * 已规划但未实现。
         */
        PLANNED("planned", true),

        /**
         * 必须等待用户确认。
         */
        CONFIRMATION_GATED("confirmation-gated", true);

        /**
         * 输出编码。
         */
        private final String code;

        /**
         * 是否需要用户确认。
         */
        private final boolean requiresConfirmation;

        GapStatus(final String code, final boolean requiresConfirmation) {
            this.code = code;
            this.requiresConfirmation = requiresConfirmation;
        }

        private String code() {
            return code;
        }

        private boolean requiresConfirmation() {
            return requiresConfirmation;
        }
    }

    /**
     * 检查状态。
     */
    private enum CheckStatus {

        /**
         * 检查通过。
         */
        PASS("PASS"),

        /**
         * 检查失败。
         */
        FAIL("FAIL");

        /**
         * 输出标签。
         */
        private final String label;

        CheckStatus(final String label) {
            this.label = label;
        }

        private String label() {
            return label;
        }
    }

    /**
     * 能力缺口定义。
     *
     * @param id 能力标识。
     * @param title 能力名称。
     * @param status 当前状态。
     * @param goal 能力目标。
     * @param evidence 证据检查。
     * @param remainingGaps 剩余缺口。
     * @param lowRiskCommands 低风险下一步命令。
     * @param confirmationCommands 需要确认的命令。
     */
    private record CapabilityGap(
            String id,
            String title,
            GapStatus status,
            String goal,
            List<EvidenceCheck> evidence,
            List<String> remainingGaps,
            List<String> lowRiskCommands,
            List<String> confirmationCommands) {
    }

    /**
     * 证据检查定义。
     *
     * @param label 证据标签。
     * @param path 文件路径。
     * @param marker 可选文本标记。
     */
    private record EvidenceCheck(String label, Path path, String marker) {
    }

    /**
     * 证据检查结果。
     *
     * @param evidence 证据定义。
     * @param exists 文件是否存在。
     * @param markerFound 文本标记是否存在。
     */
    private record EvidenceResult(EvidenceCheck evidence, boolean exists, boolean markerFound) {

        private boolean passed() {
            return exists && markerFound;
        }
    }

    /**
     * 基础检查行。
     *
     * @param status 检查状态。
     * @param name 检查名称。
     * @param message 说明。
     */
    private record CheckLine(CheckStatus status, String name, String message) {
    }

    /**
     * 缺口检查行。
     *
     * @param gap 能力缺口。
     * @param evidence 证据结果。
     */
    private record GapLine(CapabilityGap gap, List<EvidenceResult> evidence) {

        private long passedEvidenceCount() {
            return evidence.stream().filter(EvidenceResult::passed).count();
        }

        private boolean hasMissingEvidence() {
            return evidence.stream().anyMatch(result -> !result.passed());
        }
    }

    /**
     * 台账报告。
     */
    private static final class LedgerReport {

        /**
         * 基础检查结果。
         */
        private final List<CheckLine> checks = new ArrayList<>();

        /**
         * 缺口检查结果。
         */
        private final List<GapLine> gaps = new ArrayList<>();
        private final boolean allowMissingEvidence;

        private LedgerReport(final boolean allowMissingEvidence) {
            this.allowMissingEvidence = allowMissingEvidence;
        }

        private void pass(final String name, final String message) {
            checks.add(new CheckLine(CheckStatus.PASS, name, message));
        }

        private void fail(final String name, final String message) {
            checks.add(new CheckLine(CheckStatus.FAIL, name, message));
        }

        private void add(final CapabilityGap gap, final List<EvidenceResult> evidence) {
            gaps.add(new GapLine(gap, List.copyOf(evidence)));
        }

        private List<CheckLine> checks() {
            return List.copyOf(checks);
        }

        private List<GapLine> gaps() {
            return List.copyOf(gaps);
        }

        private boolean hasErrors() {
            return checks.stream().anyMatch(line -> line.status() == CheckStatus.FAIL)
                    || (!allowMissingEvidence && gaps.stream().anyMatch(GapLine::hasMissingEvidence));
        }

        private long errorCount() {
            return checks.stream().filter(line -> line.status() == CheckStatus.FAIL).count()
                    + gaps.stream().filter(GapLine::hasMissingEvidence).count();
        }

        private long countStatus(final GapStatus status) {
            return gaps.stream().filter(line -> line.gap().status() == status).count();
        }
    }
}
