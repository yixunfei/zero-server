import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * zeroServer 架构依赖边界只读守卫。
 *
 * <p>该工具面向框架贡献者和新用户，快速检查 Maven reactor 与关键模块依赖是否仍遵守
 * CONTRIBUTING.md 和 docs/module-map.md 中声明的核心边界。它只读取 POM、源码与文档，不修改依赖、
 * 不创建模块，也不替代 Maven test、quality profile 或生产就绪验证。</p>
 *
 * @author zn
 */
public final class ZeroArchitectureGuard {

    /** observability 性能基准叶子模块。 */
    private static final String BENCHMARK_MODULE = "zero-benchmarks";
    /** JMH 官方 Maven groupId。 */
    private static final String JMH_GROUP_ID = "org.openjdk.jmh";
    /** 当前确认的 JMH 版本。 */
    private static final String JMH_VERSION = "1.37";

    /**
     * 当前架构守卫关注的 Maven reactor 模块。
     */
    private static final List<String> EXPECTED_MODULES = List.of(
            "zero-parent",
            "zero-bom",
            "zero-core",
            "zero-runtime",
            "zero-runtime-bootstrap",
            "zero-runtime-event",
            "zero-runtime-actor",
            "zero-runtime-protocol",
            "zero-runtime-rpc",
            "zero-runtime-data",
            "zero-runtime-cache",
            "zero-runtime-log",
            "zero-runtime-monitor",
            "zero-discovery",
            "zero-rpc-discovery",
            "zero-runtime-discovery",
            "zero-runtime-production",
            "zero-runtime-kafka",
            "zero-runtime-mongo",
            "zero-runtime-redis",
            "zero-runtime-postgresql",
            "zero-runtime-nacos",
            "zero-runtime-net",
            "zero-event",
            "zero-protocol",
            "zero-codegen",
            "zero-actor",
            "zero-world",
            "zero-npc",
            "zero-ranking",
            "zero-room",
            "zero-game",
            "zero-player",
            "zero-scene",
            "zero-aoi",
            "zero-state-sync",
            "zero-frame-sync",
            "zero-logic",
            "zero-security",
            "zero-net",
            "zero-rpc-common",
            "zero-rpc",
            "zero-rpc-kafka",
            "zero-data",
            "zero-data-mongo",
            "zero-data-redis",
            "zero-data-postgresql",
            "zero-cache",
            "zero-discovery-nacos",
            "zero-log",
            "zero-monitor",
            "zero-gm",
            "zero-gm-rest",
            "zero-hot-update",
            "zero-server-starter",
            "zero-server-starter-production");

    /**
     * starter 默认运行时不允许以 compile/runtime 方式强依赖的真实 Adapter 模块。
     */
    private static final Set<String> REAL_ADAPTER_MODULES = Set.of(
            "zero-rpc-kafka",
            "zero-data-mongo",
            "zero-data-redis",
            "zero-data-postgresql",
            "zero-discovery-nacos");

    /**
     * production starter 必须显式 opt-in 的真实 Adapter 依赖。
     */
    private static final Set<String> PRODUCTION_REQUIRED_MODULES = Set.of(
            "zero-server-starter",
            "zero-runtime-production",
            "zero-runtime-kafka",
            "zero-runtime-mongo",
            "zero-runtime-redis",
            "zero-runtime-postgresql",
            "zero-runtime-nacos",
            "zero-runtime-net");

    /** benchmark 允许直接依赖的框架运行时模块。 */
    private static final Set<String> BENCHMARK_RUNTIME_MODULES = Set.of(
            "zero-log",
            "zero-monitor",
            "zero-net",
            "zero-server-starter-production");

    /** benchmark 允许使用的 JMH artifact。 */
    private static final Set<String> BENCHMARK_JMH_ARTIFACTS = Set.of(
            "jmh-core",
            "jmh-generator-annprocess");

    /** 允许直接持有 terminal LogSink 的顶层装配源码。 */
    private static final Set<String> TERMINAL_LOG_SINK_ASSEMBLY_FILES = Set.of(
            "zero-runtime-log/src/main/java/group/zn/zero/runtime/log/LogRuntime.java",
            "zero-server-starter/src/main/java/group/zn/zero/starter/LocalRuntime.java",
            "zero-server-starter/src/main/java/group/zn/zero/starter/LocalRuntimeBuilder.java",
            "zero-server-starter/src/main/java/group/zn/zero/starter/LocalRuntimeCapabilities.java",
            "zero-server-starter/src/main/java/group/zn/zero/starter/LocalRuntimeProviders.java",
            "zero-server-starter/src/main/java/group/zn/zero/starter/ZeroServerApplication.java",
            "zero-server-starter-production/src/main/java/group/zn/zero/starter/production/"
                    + "ZeroProductionRuntimeBuilder.java",
            "zero-server-starter-production/src/main/java/group/zn/zero/starter/production/"
                    + "ZeroProductionRuntimeFactory.java");

    /**
     * RPC 抽象层禁止直接绑定的 Adapter 或中间件关键词。
     */
    private static final Set<String> RPC_FORBIDDEN_KEYWORDS = Set.of(
            "zero-rpc-kafka",
            "zero-discovery-nacos",
            "kafka",
            "nacos");

    private static final Set<String> ACTOR_FORBIDDEN_KEYWORDS = Set.of(
            "zero-rpc",
            "kafka",
            "nacos",
            "redis",
            "mongo",
            "postgres",
            "netty");

    /**
     * Room 模块必须保持的直接依赖集合。
     */
    private static final Set<String> ROOM_ALLOWED_MODULES = Set.of("zero-actor");


    /**
     * Room 模块禁止直接绑定的中间件或上层模块关键词。
     */
    private static final Set<String> ROOM_FORBIDDEN_KEYWORDS = Set.of(
            "zero-rpc", "zero-data", "zero-cache", "zero-discovery", "zero-server-starter",
            "kafka", "nacos", "redis", "mongo", "postgres", "netty");

    /**
     * 需要在 module-map 文档中持续可定位的关键文本。
     */
    private static final List<String> MODULE_MAP_ANCHORS = List.of(
            "zero-core",
            "zero-runtime",
            "zero-server-starter",
            "zero-server-starter-production",
            "zero-rpc",
            "zero-actor",
            "zero-world",
            "zero-room",
            "zero-data",
            "zero-rpc-kafka",
            "zero-discovery-nacos",
            "zero-benchmarks",
            "LogAppender",
            "LogSink",
            "Kafka",
            "Nacos");

    private ZeroArchitectureGuard() {
    }

    /**
     * 运行架构守卫检查。
     *
     * @param args 命令行参数；支持 {@code --help}。
     * @throws IOException POM 或文档读取失败时抛出。
     */
    public static void main(final String[] args) throws IOException {
        if (hasFlag(args, "--help", "-h")) {
            printHelp();
            return;
        }
        GuardReport report = runChecks();
        printReport(report);
        if (report.hasViolations()) {
            System.exit(1);
        }
    }

    private static GuardReport runChecks() throws IOException {
        GuardReport report = new GuardReport();
        checkRepositoryRoot(report);
        List<String> rootModules = readRootModules(report);
        List<ProfileModules> rootProfiles = readRootProfiles(report);
        checkRootModules(report, rootModules);
        checkModulePoms(report);
        checkZeroCoreBoundary(report);
        checkRuntimeBoundary(report);
        checkIntegrationBoundaries(report);
        checkFoundationBoundaries(report);
        checkRpcBoundary(report);
        checkActorBoundary(report);
        checkRoomBoundary(report);
        checkDataBoundary(report);
        checkStarterBoundary(report);
        checkProductionStarterBoundary(report);
        checkObservabilityDependencyBoundary(report);
        checkTerminalLogSinkBoundary(report, rootModules);
        checkNonBenchmarkJmhBoundary(report, rootModules, rootProfiles);
        checkBenchmarkProfile(report, rootModules, rootProfiles);
        checkBenchmarkModuleBoundary(report);
        checkModuleMap(report);
        checkFrameworkBoundary(report);
        return report;
    }

    private static void checkFrameworkBoundary(final GuardReport report) throws IOException {
        Path script = Path.of("scripts", "ZeroFrameworkBoundaryGuard.java");
        if (!Files.isRegularFile(script)) {
            report.fail("framework-boundary-guard", "Missing framework boundary guard: " + script);
            return;
        }
        String source = Files.readString(script, StandardCharsets.UTF_8);
        if (!source.contains("prototype") || !source.contains("connectsExternalMiddleware")) {
            report.fail("framework-boundary-guard", "Boundary guard must check scaffold prototype and middleware metadata.");
        } else {
            report.pass("framework-boundary-guard", "Framework/template boundary guard is present and checks non-production scaffolds.");
        }
    }

    private static void checkRepositoryRoot(final GuardReport report) {
        boolean ok = Files.isRegularFile(Path.of("pom.xml"))
                && Files.isRegularFile(Path.of("CONTRIBUTING.md"))
                && Files.isRegularFile(Path.of("docs", "module-map.md"));
        if (ok) {
            report.pass("repo-root", "Current directory contains pom.xml, CONTRIBUTING.md and docs/module-map.md.");
        } else {
            report.fail("repo-root", "Run this command from the zeroServer repository root.");
        }
    }

    private static List<String> readRootModules(final GuardReport report) throws IOException {
        Path pom = Path.of("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return List.of();
        }
        try {
            return parseModules(pom);
        } catch (ParserConfigurationException | SAXException ex) {
            report.fail("root-pom-xml", "Cannot parse root pom.xml: " + ex.getMessage());
            return List.of();
        }
    }

    private static List<ProfileModules> readRootProfiles(final GuardReport report) throws IOException {
        Path pom = Path.of("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return List.of();
        }
        try {
            return parseProfiles(pom);
        } catch (ParserConfigurationException | SAXException ex) {
            report.fail("root-profile-xml", "Cannot parse root profiles: " + ex.getMessage());
            return List.of();
        }
    }

    private static void checkRootModules(final GuardReport report, final List<String> rootModules) {
        List<String> missing = EXPECTED_MODULES.stream()
                .filter(module -> !rootModules.contains(module))
                .toList();
        List<String> extras = rootModules.stream()
                .filter(module -> module.startsWith("zero-") && !EXPECTED_MODULES.contains(module))
                .toList();
        if (missing.isEmpty()) {
            report.pass("root-modules", "Root reactor declares all " + EXPECTED_MODULES.size()
                    + " expected modules.");
        } else {
            report.fail("root-modules", "Missing root reactor modules: " + String.join(", ", missing));
        }
        if (!extras.isEmpty()) {
            report.warn("root-modules-extra", "Extra zero-* modules are not covered by guard rules yet: "
                    + String.join(", ", extras));
        }
    }

    private static void checkModulePoms(final GuardReport report) {
        List<String> missing = EXPECTED_MODULES.stream()
                .filter(module -> !Files.isRegularFile(Path.of(module, "pom.xml")))
                .toList();
        if (missing.isEmpty()) {
            report.pass("module-poms", "All expected module pom.xml files exist.");
        } else {
            report.fail("module-poms", "Missing module pom.xml files: " + String.join(", ", missing));
        }
    }

    private static void checkZeroCoreBoundary(final GuardReport report) throws IOException {
        List<Dependency> dependencies = dependenciesOf(report, "zero-core");
        if (dependencies.isEmpty()) {
            report.pass("zero-core-boundary", "zero-core declares no direct dependencies.");
        } else {
            report.fail("zero-core-boundary", "zero-core must stay dependency-free, found: "
                    + dependencyNames(dependencies));
        }
    }

    private static void checkRuntimeBoundary(final GuardReport report) throws IOException {
        List<Dependency> nonTestDependencies = dependenciesOf(report, "zero-runtime").stream()
                .filter(dependency -> !dependency.isTestOnly())
                .toList();
        boolean onlyDependsOnCore = nonTestDependencies.size() == 1
                && "group.zn.zero".equals(nonTestDependencies.get(0).groupId())
                && "zero-core".equals(nonTestDependencies.get(0).artifactId());
        if (onlyDependsOnCore) {
            report.pass("zero-runtime-boundary", "zero-runtime directly depends only on zero-core at runtime.");
        } else {
            report.fail("zero-runtime-boundary", "zero-runtime must have exactly one non-test direct dependency, "
                    + "group.zn.zero:zero-core, found: " + dependencyNames(nonTestDependencies));
        }
    }

    private static void checkFoundationBoundaries(final GuardReport report) throws IOException {
        List<String> modules = List.of("zero-event", "zero-protocol", "zero-actor");
        List<String> violations = new ArrayList<>();
        for (String module : modules) {
            List<Dependency> unexpected = dependenciesOf(report, module).stream()
                    .filter(dependency -> !"zero-core".equals(dependency.artifactId()))
                    .toList();
            if (!unexpected.isEmpty()) {
                violations.add(module + " -> " + dependencyNames(unexpected));
            }
        }
        if (violations.isEmpty()) {
            report.pass("foundation-boundaries", "zero-event, zero-protocol and zero-actor depend only on zero-core.");
        } else {
            report.fail("foundation-boundaries", "Unexpected foundational dependencies: "
                    + String.join("; ", violations));
        }
    }

    private static void checkRpcBoundary(final GuardReport report) throws IOException {
        List<Dependency> forbidden = dependenciesOf(report, "zero-rpc").stream()
                .filter(ZeroArchitectureGuard::matchesRpcForbidden)
                .toList();
        if (forbidden.isEmpty()) {
            report.pass("zero-rpc-boundary", "zero-rpc does not directly depend on Kafka/Nacos adapters or SDKs.");
        } else {
            report.fail("zero-rpc-boundary", "zero-rpc has forbidden concrete dependencies: "
                    + dependencyNames(forbidden));
        }
    }

    private static void checkActorBoundary(final GuardReport report) throws IOException {
        List<Dependency> forbidden = dependenciesOf(report, "zero-actor").stream()
                .filter(ZeroArchitectureGuard::matchesActorForbidden)
                .toList();
        if (forbidden.isEmpty()) {
            report.pass("zero-actor-boundary", "zero-actor does not directly bind RPC or middleware dependencies.");
        } else {
            report.fail("zero-actor-boundary", "zero-actor has forbidden direct dependencies: "
                    + dependencyNames(forbidden));
        }
    }

    private static void checkRoomBoundary(final GuardReport report) throws IOException {
        List<Dependency> dependencies = dependenciesOf(report, "zero-room").stream()
                .filter(dependency -> !dependency.isTestOnly())
                .toList();
        List<Dependency> unexpected = dependencies.stream()
                .filter(dependency -> !ROOM_ALLOWED_MODULES.contains(dependency.artifactId()))
                .toList();
        List<Dependency> forbidden = dependencies.stream()
                .filter(ZeroArchitectureGuard::matchesRoomForbidden)
                .toList();
        if (unexpected.isEmpty() && forbidden.isEmpty()) {
            report.pass("zero-room-boundary", "zero-room depends only on zero-actor and stays middleware-free.");
        } else {
            report.fail("zero-room-boundary", "zero-room has forbidden direct dependencies: "
                    + dependencyNames(unexpected.isEmpty() ? forbidden : unexpected));
        }
    }

    private static void checkDataBoundary(final GuardReport report) throws IOException {
        List<Dependency> forbidden = dependenciesOf(report, "zero-data").stream()
                .filter(dependency -> "zero-actor".equals(dependency.artifactId()))
                .toList();
        if (forbidden.isEmpty()) {
            report.pass("zero-data-boundary", "zero-data does not depend on zero-actor.");
        } else {
            report.fail("zero-data-boundary", "zero-data must not depend on zero-actor: "
                    + dependencyNames(forbidden));
        }
    }

    private static void checkStarterBoundary(final GuardReport report) throws IOException {
        List<Dependency> forbidden = dependenciesOf(report, "zero-server-starter").stream()
                .filter(dependency -> REAL_ADAPTER_MODULES.contains(dependency.artifactId()))
                .filter(dependency -> !dependency.isTestOnly())
                .toList();
        if (forbidden.isEmpty()) {
            report.pass("starter-local-boundary", "zero-server-starter keeps real adapters outside compile/runtime.");
        } else {
            report.fail("starter-local-boundary", "zero-server-starter has non-test real adapter dependencies: "
                    + dependencyNames(forbidden));
        }
    }

    private static void checkProductionStarterBoundary(final GuardReport report) throws IOException {
        List<Dependency> dependencies = dependenciesOf(report, "zero-server-starter-production");
        Set<String> present = new HashSet<>();
        for (Dependency dependency : dependencies) {
            if (!dependency.isTestOnly()) {
                present.add(dependency.artifactId());
            }
        }
        List<String> missing = PRODUCTION_REQUIRED_MODULES.stream()
                .filter(module -> !present.contains(module))
                .toList();
        if (missing.isEmpty()) {
            report.pass("production-starter-boundary", "zero-server-starter-production explicitly depends on starter "
                    + "and independently consumable integration modules.");
        } else {
            report.fail("production-starter-boundary", "zero-server-starter-production missing dependencies: "
                    + String.join(", ", missing));
        }
    }

    private static void checkIntegrationBoundaries(final GuardReport report) throws IOException {
        List<String> violations = new ArrayList<>();
        for (String module : EXPECTED_MODULES) {
            if (!module.startsWith("zero-runtime-") && !"zero-discovery".equals(module)
                    && !"zero-rpc-discovery".equals(module)) {
                continue;
            }
            Set<String> closure = new HashSet<>();
            collectRuntimeClosure(report, module, closure);
            if (closure.stream().anyMatch(dependency -> dependency.startsWith("zero-server-starter"))) {
                violations.add(module + " depends on a Starter");
            }
            Set<String> adapters = new HashSet<>(closure);
            adapters.retainAll(REAL_ADAPTER_MODULES);
            String allowed = switch (module) {
                case "zero-runtime-kafka" -> "zero-rpc-kafka";
                case "zero-runtime-mongo" -> "zero-data-mongo";
                case "zero-runtime-redis" -> "zero-data-redis";
                case "zero-runtime-postgresql" -> "zero-data-postgresql";
                case "zero-runtime-nacos" -> "zero-discovery-nacos";
                default -> "";
            };
            adapters.remove(allowed);
            if (!adapters.isEmpty()) {
                violations.add(module + " pulls unrelated adapters " + adapters.stream().sorted().toList());
            }
        }
        if (violations.isEmpty()) {
            report.pass("integration-boundaries", "Integration runtime closures exclude Starters and unrelated adapters.");
        } else {
            report.fail("integration-boundaries", String.join("; ", violations));
        }
    }

    private static void collectRuntimeClosure(
            final GuardReport report, final String module, final Set<String> visited) throws IOException {
        if (!visited.add(module)) {
            return;
        }
        for (Dependency dependency : dependenciesOf(report, module)) {
            if (!dependency.isTestOnly() && "group.zn.zero".equals(dependency.groupId())) {
                collectRuntimeClosure(report, dependency.artifactId(), visited);
            }
        }
    }

    private static void checkObservabilityDependencyBoundary(final GuardReport report) throws IOException {
        List<String> violations = new ArrayList<>();
        collectForbiddenDependencies(report, violations, "zero-core", Set.of("zero-log", "zero-monitor"));
        collectForbiddenDependencies(report, violations, "zero-net", Set.of("zero-log", "zero-monitor"));
        collectForbiddenDependencies(report, violations, "zero-monitor", Set.of("zero-log"));
        if (violations.isEmpty()) {
            report.pass("observability-dependencies", "core, net and monitor keep observability dependency direction.");
        } else {
            report.fail("observability-dependencies", "Forbidden observability dependencies: "
                    + String.join("; ", violations));
        }
    }

    private static void collectForbiddenDependencies(
            final GuardReport report,
            final List<String> violations,
            final String module,
            final Set<String> forbiddenArtifacts) throws IOException {
        List<Dependency> forbidden = dependenciesOf(report, module).stream()
                .filter(dependency -> forbiddenArtifacts.contains(dependency.artifactId()))
                .toList();
        if (!forbidden.isEmpty()) {
            violations.add(module + " -> " + dependencyNames(forbidden));
        }
    }

    private static void checkTerminalLogSinkBoundary(
            final GuardReport report,
            final List<String> rootModules) throws IOException {
        List<Path> sourceFiles = formalSourceFiles(rootModules);
        List<String> violations = new ArrayList<>();
        for (Path sourceFile : sourceFiles) {
            String normalized = normalizedPath(sourceFile);
            if (TERMINAL_LOG_SINK_ASSEMBLY_FILES.contains(normalized)) {
                continue;
            }
            String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
            if (containsCodeIdentifier(source, "LogSink")) {
                violations.add(normalized);
            }
        }
        if (violations.isEmpty()) {
            report.pass("terminal-log-sink-boundary", "Formal callers use LogAppender outside terminal assembly.");
        } else {
            report.fail("terminal-log-sink-boundary", "Formal source bypasses the LogAppender boundary: "
                    + String.join(", ", violations));
        }
    }

    private static List<Path> formalSourceFiles(final List<String> rootModules) throws IOException {
        List<Path> sourceFiles = new ArrayList<>();
        for (String module : rootModules) {
            if (!"zero-log".equals(module) && !BENCHMARK_MODULE.equals(module)) {
                collectSourceFiles(Path.of(module, "src", "main", "java"), ".java", sourceFiles);
            }
        }
        Path examplesRoot = Path.of("examples");
        if (Files.isDirectory(examplesRoot)) {
            try (var examples = Files.list(examplesRoot)) {
                for (Path example : examples.filter(Files::isDirectory).sorted().toList()) {
                    collectSourceFiles(example.resolve(Path.of("src", "main", "java")), ".java", sourceFiles);
                }
            }
        }
        collectSourceFiles(Path.of("templates"), ".java.tpl", sourceFiles);
        return sourceFiles;
    }

    private static void collectSourceFiles(
            final Path root,
            final String suffix,
            final List<Path> destination) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(suffix))
                    .sorted()
                    .forEach(destination::add);
        }
    }

    private static void checkNonBenchmarkJmhBoundary(
            final GuardReport report,
            final List<String> rootModules,
            final List<ProfileModules> rootProfiles) throws IOException {
        List<String> violations = new ArrayList<>();
        Set<String> candidateModules = new HashSet<>(rootModules);
        for (ProfileModules profile : rootProfiles) {
            candidateModules.addAll(profile.modules());
        }
        for (String module : candidateModules.stream().sorted().toList()) {
            if (BENCHMARK_MODULE.equals(module)) {
                continue;
            }
            Path pom = Path.of(module, "pom.xml");
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            String text = Files.readString(pom, StandardCharsets.UTF_8);
            if (text.contains("<artifactId>zero-benchmarks</artifactId>")
                    || text.contains(JMH_GROUP_ID)) {
                violations.add(module);
            }
        }
        if (violations.isEmpty()) {
            report.pass("runtime-benchmark-boundary", "Non-benchmark modules do not depend on benchmark/JMH code.");
        } else {
            report.fail("runtime-benchmark-boundary", "Non-benchmark modules reference zero-benchmarks or JMH: "
                    + String.join(", ", violations));
        }
    }

    private static void checkBenchmarkProfile(
            final GuardReport report,
            final List<String> rootModules,
            final List<ProfileModules> rootProfiles) {
        List<ProfileModules> owners = rootProfiles.stream()
                .filter(profile -> profile.modules().contains(BENCHMARK_MODULE))
                .toList();
        boolean defaultReactorClean = !rootModules.contains(BENCHMARK_MODULE);
        boolean singleFrozenProfile = owners.size() == 1
                && "benchmarks".equals(owners.get(0).id())
                && owners.get(0).modules().equals(List.of(BENCHMARK_MODULE))
                && !owners.get(0).activationConfigured();
        if (defaultReactorClean && singleFrozenProfile) {
            report.pass("benchmark-profile", "zero-benchmarks is enabled only by the root benchmarks profile.");
        } else {
            report.fail("benchmark-profile", "zero-benchmarks must be absent from default modules and declared only "
                    + "by explicitly selected profile benchmarks with no activation or companion modules.");
        }
    }

    private static void checkBenchmarkModuleBoundary(final GuardReport report) throws IOException {
        List<Dependency> dependencies = dependenciesOf(report, BENCHMARK_MODULE);
        Set<String> runtimeModules = new HashSet<>();
        boolean jmhCorePresent = false;
        boolean protocolTestPresent = false;
        List<Dependency> unexpected = new ArrayList<>();
        for (Dependency dependency : dependencies) {
            if ("group.zn.zero".equals(dependency.groupId())
                    && BENCHMARK_RUNTIME_MODULES.contains(dependency.artifactId())
                    && "compile".equals(dependency.scope())) {
                runtimeModules.add(dependency.artifactId());
            } else if (JMH_GROUP_ID.equals(dependency.groupId())
                    && "jmh-core".equals(dependency.artifactId())
                    && "compile".equals(dependency.scope())
                    && isFrozenJmhVersion(dependency.version())) {
                jmhCorePresent = true;
            } else if ("group.zn.zero".equals(dependency.groupId())
                    && "zero-protocol".equals(dependency.artifactId())
                    && "test".equals(dependency.scope())) {
                // The opt-in protocol gate exercises codec classes without adding the
                // protocol module to the runtime class path of the benchmark artifact.
                protocolTestPresent = true;
            } else {
                unexpected.add(dependency);
            }
        }
        BenchmarkJmhMetadata metadata = readBenchmarkJmhMetadata(report);
        // 运行时依赖 + jmh-core + 可选 test 作用域 zero-protocol；注解处理器只出现在 plugin 中。
        int expectedDependencies = BENCHMARK_RUNTIME_MODULES.size() + 1 + (protocolTestPresent ? 1 : 0);
        boolean frozen = runtimeModules.equals(BENCHMARK_RUNTIME_MODULES)
                && jmhCorePresent
                && dependencies.size() == expectedDependencies
                && unexpected.isEmpty()
                && JMH_VERSION.equals(metadata.propertyVersion())
                && metadata.artifacts().equals(BENCHMARK_JMH_ARTIFACTS)
                && metadata.referencesUseFrozenVersion();
        if (frozen) {
            report.pass("benchmark-module", "zero-benchmarks has the frozen runtime dependencies and JMH 1.37.");
        } else {
            report.fail("benchmark-module", "zero-benchmarks dependencies/JMH references differ from the frozen set; "
                    + "runtime=" + runtimeModules + ", unexpected=" + dependencyNames(unexpected)
                    + ", jmhArtifacts=" + metadata.artifacts() + ", jmhVersion=" + metadata.propertyVersion() + ".");
        }
    }

    private static BenchmarkJmhMetadata readBenchmarkJmhMetadata(final GuardReport report) throws IOException {
        Path pom = Path.of(BENCHMARK_MODULE, "pom.xml");
        if (!Files.isRegularFile(pom)) {
            report.fail("benchmark-pom", "Missing " + pom + ".");
            return new BenchmarkJmhMetadata("", Set.of(), false);
        }
        try {
            return parseBenchmarkJmhMetadata(pom);
        } catch (ParserConfigurationException | SAXException ex) {
            report.fail("benchmark-pom-xml", "Cannot parse " + pom + ": " + ex.getMessage());
            return new BenchmarkJmhMetadata("", Set.of(), false);
        }
    }

    private static boolean isFrozenJmhVersion(final String version) {
        return JMH_VERSION.equals(version) || "${jmh.version}".equals(version);
    }

    private static void checkModuleMap(final GuardReport report) throws IOException {
        Path moduleMap = Path.of("docs", "module-map.md");
        if (!Files.isRegularFile(moduleMap)) {
            report.fail("module-map", "Missing docs/module-map.md.");
            return;
        }
        String text = Files.readString(moduleMap, StandardCharsets.UTF_8);
        List<String> missing = MODULE_MAP_ANCHORS.stream()
                .filter(anchor -> !text.contains(anchor))
                .toList();
        if (missing.isEmpty()) {
            report.pass("module-map", "docs/module-map.md contains the key guarded module anchors.");
        } else {
            report.fail("module-map", "docs/module-map.md missing guarded anchors: "
                    + String.join(", ", missing));
        }
    }

    private static boolean matchesRpcForbidden(final Dependency dependency) {
        String value = dependency.searchText();
        for (String keyword : RPC_FORBIDDEN_KEYWORDS) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesActorForbidden(final Dependency dependency) {
        String value = dependency.searchText();
        for (String keyword : ACTOR_FORBIDDEN_KEYWORDS) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesRoomForbidden(final Dependency dependency) {
        String value = dependency.searchText();
        for (String keyword : ROOM_FORBIDDEN_KEYWORDS) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizedPath(final Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static boolean containsCodeIdentifier(final String source, final String identifier) {
        int state = 0;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (state == 1) {
                if (current == '\n' || current == '\r') {
                    state = 0;
                }
                continue;
            }
            if (state == 2) {
                if (current == '*' && next == '/') {
                    state = 0;
                    index++;
                }
                continue;
            }
            if (state == 3 || state == 4) {
                if (current == '\\') {
                    index++;
                } else if ((state == 3 && current == '"') || (state == 4 && current == '\'')) {
                    state = 0;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                state = 1;
                index++;
                continue;
            }
            if (current == '/' && next == '*') {
                state = 2;
                index++;
                continue;
            }
            if (current == '"') {
                state = 3;
                continue;
            }
            if (current == '\'') {
                state = 4;
                continue;
            }
            if (source.startsWith(identifier, index)
                    && isIdentifierBoundary(source, index - 1)
                    && isIdentifierBoundary(source, index + identifier.length())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIdentifierBoundary(final String source, final int index) {
        return index < 0 || index >= source.length() || !Character.isJavaIdentifierPart(source.charAt(index));
    }

    private static List<Dependency> dependenciesOf(final GuardReport report, final String module) throws IOException {
        Path pom = Path.of(module, "pom.xml");
        if (!Files.isRegularFile(pom)) {
            report.fail(module + "-pom", "Missing " + pom + ".");
            return List.of();
        }
        try {
            return parseDependencies(pom);
        } catch (ParserConfigurationException | SAXException ex) {
            report.fail(module + "-pom-xml", "Cannot parse " + pom + ": " + ex.getMessage());
            return List.of();
        }
    }

    private static List<String> parseModules(final Path pom)
            throws ParserConfigurationException, IOException, SAXException {
        Document document = parseXml(pom);
        Element modulesElement = firstDirectChild(document.getDocumentElement(), "modules");
        return moduleNames(modulesElement);
    }

    private static List<ProfileModules> parseProfiles(final Path pom)
            throws ParserConfigurationException, IOException, SAXException {
        Document document = parseXml(pom);
        Element profilesElement = firstDirectChild(document.getDocumentElement(), "profiles");
        if (profilesElement == null) {
            return List.of();
        }
        List<ProfileModules> profiles = new ArrayList<>();
        for (Element profileElement : directChildren(profilesElement, "profile")) {
            profiles.add(new ProfileModules(
                    directChildText(profileElement, "id"),
                    moduleNames(firstDirectChild(profileElement, "modules")),
                    firstDirectChild(profileElement, "activation") != null));
        }
        return List.copyOf(profiles);
    }

    private static List<String> moduleNames(final Element modulesElement) {
        if (modulesElement == null) {
            return List.of();
        }
        List<String> modules = new ArrayList<>();
        for (Element moduleElement : directChildren(modulesElement, "module")) {
            String module = moduleElement.getTextContent().trim();
            if (!module.isBlank()) {
                modules.add(module);
            }
        }
        return List.copyOf(modules);
    }

    private static BenchmarkJmhMetadata parseBenchmarkJmhMetadata(final Path pom)
            throws ParserConfigurationException, IOException, SAXException {
        Document document = parseXml(pom);
        Element properties = firstDirectChild(document.getDocumentElement(), "properties");
        String propertyVersion = properties == null ? "" : directChildText(properties, "jmh.version");
        Set<String> artifacts = new HashSet<>();
        boolean frozenVersions = true;
        var groupElements = document.getElementsByTagNameNS("*", "groupId");
        for (int index = 0; index < groupElements.getLength(); index++) {
            Node groupNode = groupElements.item(index);
            if (!JMH_GROUP_ID.equals(groupNode.getTextContent().trim())
                    || !(groupNode.getParentNode() instanceof Element owner)) {
                continue;
            }
            String artifactId = directChildText(owner, "artifactId");
            String version = directChildText(owner, "version");
            if (!artifactId.isBlank()) {
                artifacts.add(artifactId);
            }
            if (!isFrozenJmhVersion(version)) {
                frozenVersions = false;
            }
        }
        return new BenchmarkJmhMetadata(propertyVersion, Set.copyOf(artifacts), frozenVersions);
    }

    private static List<Dependency> parseDependencies(final Path pom)
            throws ParserConfigurationException, IOException, SAXException {
        Document document = parseXml(pom);
        Element dependenciesElement = firstDirectChild(document.getDocumentElement(), "dependencies");
        if (dependenciesElement == null) {
            return List.of();
        }
        List<Dependency> dependencies = new ArrayList<>();
        for (Element dependencyElement : directChildren(dependenciesElement, "dependency")) {
            String groupId = directChildText(dependencyElement, "groupId");
            String artifactId = directChildText(dependencyElement, "artifactId");
            String version = directChildText(dependencyElement, "version");
            String scope = directChildText(dependencyElement, "scope");
            if (!artifactId.isBlank()) {
                dependencies.add(new Dependency(
                        groupId,
                        artifactId,
                        version,
                        scope.isBlank() ? "compile" : scope));
            }
        }
        return dependencies;
    }

    private static Document parseXml(final Path pom)
            throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(pom.toFile());
    }

    private static Element firstDirectChild(final Element parent, final String localName) {
        for (Element element : directChildren(parent, localName)) {
            return element;
        }
        return null;
    }

    private static List<Element> directChildren(final Element parent, final String localName) {
        List<Element> elements = new ArrayList<>();
        Node child = parent.getFirstChild();
        while (child != null) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                elements.add(element);
            }
            child = child.getNextSibling();
        }
        return elements;
    }

    private static String directChildText(final Element parent, final String localName) {
        Element child = firstDirectChild(parent, localName);
        return child == null ? "" : child.getTextContent().trim();
    }

    private static String dependencyNames(final List<Dependency> dependencies) {
        return dependencies.stream()
                .map(Dependency::displayName)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
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

    private static void printReport(final GuardReport report) {
        System.out.println("zeroServer architecture guard");
        for (CheckLine result : report.results()) {
            System.out.println("[" + result.status().label() + "] " + result.name() + " - " + result.message());
        }
        System.out.println();
        if (report.hasViolations()) {
            System.out.println("zero-architecture-guard=failed"
                    + "|modules=" + report.expectedModuleCount()
                    + "|rules=" + report.ruleCount()
                    + "|violations=" + report.violationCount()
                    + "|warnings=" + report.warningCount());
            System.out.println();
            System.out.println("Fix dependency boundaries or update the guarded design after confirmation, then rerun:");
            System.out.println("  java scripts/ZeroArchitectureGuard.java");
            return;
        }
        System.out.println("zero-architecture-guard=ok"
                + "|modules=" + report.expectedModuleCount()
                + "|rules=" + report.ruleCount()
                + "|violations=0"
                + "|warnings=" + report.warningCount());
        System.out.println();
        System.out.println("Note: architecture guard is a static POM/source/document check, not a full Maven "
                + "or production gate.");
    }

    private static void printHelp() {
        System.out.println("zeroServer architecture guard");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java scripts/ZeroArchitectureGuard.java");
        System.out.println("  java scripts/ZeroArchitectureGuard.java --help");
        System.out.println();
        System.out.println("Checks:");
        System.out.println("  root reactor module list");
        System.out.println("  expected module pom.xml files");
        System.out.println("  zero-core dependency-free boundary");
        System.out.println("  zero-runtime single zero-core dependency boundary");
        System.out.println("  zero-event / zero-protocol / zero-actor foundational dependencies");
        System.out.println("  zero-room local room dependency and middleware-free boundary");
        System.out.println("  zero-rpc, zero-actor and zero-data forbidden dependency boundaries");
        System.out.println("  zero-server-starter local/default adapter boundary");
        System.out.println("  zero-server-starter-production explicit adapter opt-in boundary");
        System.out.println("  observability dependency direction and terminal LogSink source boundary");
        System.out.println("  opt-in zero-benchmarks profile, dependency set and JMH 1.37 boundary");
        System.out.println("  docs/module-map.md guarded anchors");
        System.out.println("  framework/template boundary and non-production scaffold markers");
        System.out.println();
        System.out.println("This tool is read-only. It does not modify POM/source files, create modules, run Docker "
                + "or prove production readiness.");
    }

    /**
     * Maven dependency direct edge.
     *
     * @param groupId 依赖 groupId。
     * @param artifactId 依赖 artifactId。
     * @param version 依赖版本；可为空或属性引用。
     * @param scope Maven scope；空值按 compile 处理。
     */
    private record Dependency(String groupId, String artifactId, String version, String scope) {

        private boolean isTestOnly() {
            return "test".equals(scope);
        }

        private String searchText() {
            return (groupId + ":" + artifactId).toLowerCase(Locale.ROOT);
        }

        private String displayName() {
            return groupId + ":" + artifactId + ":" + scope;
        }
    }

    /**
     * 根 Maven profile 及其直接模块。
     *
     * @param id profile id；可为空。
     * @param modules 不可变、有序模块列表；不可为空。
     * @param activationConfigured 是否声明了自动激活条件。
     */
    private record ProfileModules(String id, List<String> modules, boolean activationConfigured) {
    }

    /**
     * benchmark POM 中的 JMH 冻结信息。
     *
     * @param propertyVersion jmh.version 属性值；可为空。
     * @param artifacts 不可变、无序 JMH artifact 集合；不可为空。
     * @param referencesUseFrozenVersion 所有 JMH 引用是否使用冻结版本或统一属性。
     */
    private record BenchmarkJmhMetadata(
            String propertyVersion,
            Set<String> artifacts,
            boolean referencesUseFrozenVersion) {
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
         * 检查警告。
         */
        WARN("WARN"),

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
     * 单条检查结果。
     *
     * @param status 检查状态。
     * @param name 检查名称。
     * @param message 检查说明。
     */
    private record CheckLine(CheckStatus status, String name, String message) {
    }

    /**
     * 架构守卫报告。
     */
    private static final class GuardReport {

        /**
         * 所有检查结果。
         */
        private final List<CheckLine> results = new ArrayList<>();

        private void pass(final String name, final String message) {
            results.add(new CheckLine(CheckStatus.PASS, name, message));
        }

        private void warn(final String name, final String message) {
            results.add(new CheckLine(CheckStatus.WARN, name, message));
        }

        private void fail(final String name, final String message) {
            results.add(new CheckLine(CheckStatus.FAIL, name, message));
        }

        private List<CheckLine> results() {
            return List.copyOf(results);
        }

        private boolean hasViolations() {
            return results.stream().anyMatch(line -> line.status() == CheckStatus.FAIL);
        }

        private long violationCount() {
            return results.stream().filter(line -> line.status() == CheckStatus.FAIL).count();
        }

        private long warningCount() {
            return results.stream().filter(line -> line.status() == CheckStatus.WARN).count();
        }

        private int ruleCount() {
            return results.size();
        }

        private int expectedModuleCount() {
            return EXPECTED_MODULES.size();
        }
    }
}
