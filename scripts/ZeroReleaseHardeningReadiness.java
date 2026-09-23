import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * zeroServer 发布硬化材料只读验证器。
 *
 * <p>该工具检查开源治理文件、Issue 模板、CI / Maven 分层 marker、发布检查单与迁移模板。
 * 它不启动 Maven、网络或外部进程，不执行测试、签名、发布或部署，也不代表 production ready。</p>
 *
 * @author zn
 */
public final class ZeroReleaseHardeningReadiness {

    /**
     * 发布准备所需路径。
     */
    private static final List<RequiredPath> REQUIRED_PATHS = List.of(
            path("release-policy", "docs", "git-workflow.zh-CN.md"),
            path("changelog", "CHANGELOG.md"),
            path("contributing", "CONTRIBUTING.md"),
            path("security", "SECURITY.md"),
            path("code-of-conduct", "CODE_OF_CONDUCT.md"),
            path("license", "LICENSE"),
            path("bug-report-template", ".github", "ISSUE_TEMPLATE", "bug_report.md"),
            path("feature-request-template", ".github", "ISSUE_TEMPLATE", "feature_request.md"),
            path("design-proposal-template", ".github", "ISSUE_TEMPLATE", "design_proposal.md"),
            path("ci-workflow", ".github", "workflows", "ci.yml"),
            path("root-pom", "pom.xml"),
            path("parent-pom", "zero-parent", "pom.xml"),
            path("git-workflow", "docs", "git-workflow.zh-CN.md"),
            path("module-map", "docs", "module-map.md"),
            path("build-smoke", "scripts", "ZeroUnifiedEntryVerifier.java"),
            path("framework-boundary", "scripts", "ZeroFrameworkBoundaryGuard.java"),
            path("release-readiness", "docs", "operations", "release-hardening-readiness.zh-CN.md"),
            path("release-checklist", "docs", "operations", "release-checklist.zh-CN.md"),
            path("migration-template", "docs", "migrations", "template.zh-CN.md"));

    /**
     * 发布准备材料中的稳定 marker。
     */
    private static final List<RequiredMarker> REQUIRED_MARKERS = List.of(
            marker("release-authorization", "docs/operations/release-checklist.zh-CN.md", "releaseAuthorization=false"),
            marker("zero-x-rule", "docs/git-workflow.zh-CN.md", "0.x"),
            marker("changelog-unreleased", "CHANGELOG.md", "## Unreleased"),
            marker("contributing-quality", "CONTRIBUTING.md", "zero-contributing-quality=required"),
            marker("security-reporting", "SECURITY.md", "zero-security-reporting=private-channel"),
            marker("conduct-title", "CODE_OF_CONDUCT.md", "# Code of Conduct"),
            marker("mit-license", "LICENSE", "MIT License"),
            marker("bug-reproduction", ".github/ISSUE_TEMPLATE/bug_report.md", "zero-bug-reproduction=required"),
            marker("feature-risk", ".github/ISSUE_TEMPLATE/feature_request.md", "zero-feature-risk=required"),
            marker("design-performance", ".github/ISSUE_TEMPLATE/design_proposal.md", "zero-design-performance-and-verification=required"),
            marker("design-verification", ".github/ISSUE_TEMPLATE/design_proposal.md", "zero-design-performance-and-verification=required"),
            marker("ci-java-21", ".github/workflows/ci.yml", "java-version: '21'"),
            marker("ci-validate", ".github/workflows/ci.yml", "mvn -B -ntp -DskipTests validate"),
            marker("ci-default-tests", ".github/workflows/ci.yml", "mvn -B -ntp test"),
            marker("ci-quality", ".github/workflows/ci.yml", "mvn -B -ntp -Pquality verify"),
            marker("ci-manual", ".github/workflows/ci.yml", "workflow_dispatch:"),
            marker("maven-java-21", "zero-parent/pom.xml", "<maven.compiler.release>21"),
            marker("maven-quality", "zero-parent/pom.xml", "<id>quality</id>"),
            marker("maven-integration", "zero-parent/pom.xml", "<id>integration-tests</id>"),
            marker("maven-external", "zero-parent/pom.xml", "<id>external-tests</id>"),
            marker("git-verification", "docs/git-workflow.zh-CN.md", "zero-git-verification=required"),
            marker("module-map-core", "docs/module-map.md", "zero-core"),
            marker("build-smoke-summary", "scripts/ZeroUnifiedEntryVerifier.java",
                    "zero-unified-entry-verifier=ok"),
            marker("framework-boundary-summary", "scripts/ZeroFrameworkBoundaryGuard.java",
                    "zero-framework-boundary-guard=ok"),
            marker("readiness-state", "docs/operations/release-hardening-readiness.zh-CN.md", "partial-evidence"),
            marker("readiness-confirmation", "docs/operations/release-hardening-readiness.zh-CN.md",
                    "requiresConfirmation=true"),
            marker("checklist-scope", "docs/operations/release-checklist.zh-CN.md", "scope=true"),
            marker("checklist-levels", "docs/operations/release-checklist.zh-CN.md", "layeredVerification=true"),
            marker("checklist-rollback", "docs/operations/release-checklist.zh-CN.md", "rollbackRecovery=true"),
            marker("migration-breaking", "docs/migrations/template.zh-CN.md", "breakingChanges=true"),
            marker("migration-verification", "docs/migrations/template.zh-CN.md", "zero-migration-verification-and-rollback=required"),
            marker("migration-rollback", "docs/migrations/template.zh-CN.md", "zero-migration-verification-and-rollback=required"));

    /**
     * 明确区分的验证与外部动作层级。
     */
    private static final List<ReadinessLevel> LEVELS = List.of(
            new ReadinessLevel("validate", "Maven 模型与基础构建阶段；不运行测试。"),
            new ReadinessLevel("default-tests", "默认单元与无真实中间件测试。"),
            new ReadinessLevel("quality", "Checkstyle、PMD、SpotBugs 与 JaCoCo 报告。"),
            new ReadinessLevel("integration-tests", "按改动选择的本地集成测试。"),
            new ReadinessLevel("external-tests", "需要独立环境记录的真实中间件验证。"),
            new ReadinessLevel("performance", "需要单独确认口径的 JMH、压测、容量与长稳。"),
            new ReadinessLevel("release-actions", "版本、tag、签名、发布与部署外部动作。"));

    private ZeroReleaseHardeningReadiness() {
    }

    /**
     * 运行发布硬化材料只读验证。
     *
     * @param args 命令行参数；支持 {@code --listChecks} 与 {@code --help}。
     * @throws IOException 文件读取失败时抛出；方法不修改数据，线程安全。
     */
    public static void main(final String[] args) throws IOException {
        if (hasFlag(args, "--help", "-h")) {
            printHelp();
            return;
        }
        if (hasFlag(args, "--listChecks", "--list-checks")) {
            printChecks();
            return;
        }
        ReadinessReport report = inspect(hasFlag(args, "--allowMissingEvidence", "--allow-missing-evidence"));
        printReport(report);
        if (report.hasErrors()) {
            System.exit(1);
        }
    }

    private static ReadinessReport inspect(final boolean allowMissingEvidence) throws IOException {
        ReadinessReport report = new ReadinessReport(allowMissingEvidence);
        if (!Files.isRegularFile(Path.of("pom.xml"))) {
            report.repositoryOk = false;
            return report;
        }
        report.repositoryOk = true;
        for (RequiredPath required : REQUIRED_PATHS) {
            report.paths.add(new PathResult(required, Files.isRegularFile(required.path())));
        }
        for (RequiredMarker required : REQUIRED_MARKERS) {
            boolean present = Files.isRegularFile(required.path())
                    && Files.readString(required.path(), StandardCharsets.UTF_8).contains(required.token());
            report.markers.add(new MarkerResult(required, present));
        }
        return report;
    }

    private static void printReport(final ReadinessReport report) {
        System.out.println("zeroServer release hardening readiness");
        System.out.println();
        System.out.println("Repository:");
        System.out.println("[" + (report.repositoryOk ? "PASS" : "FAIL") + "] repo-root");
        System.out.println();
        System.out.println("Required paths:");
        for (PathResult result : report.paths) {
            System.out.println("[" + status(result.present()) + "] path=" + result.required().name()
                    + "|value=" + result.required().path());
        }
        System.out.println();
        System.out.println("Required markers:");
        for (MarkerResult result : report.markers) {
            System.out.println("[" + status(result.present()) + "] marker=" + result.required().name()
                    + "|path=" + result.required().path());
        }
        System.out.println();
        System.out.println("Verification and action levels:");
        for (ReadinessLevel level : LEVELS) {
            System.out.println("[INFO] level=" + level.name() + "|scope=" + level.scope());
        }
        System.out.println();
        if (report.hasErrors()) {
            System.out.println("zero-release-hardening-readiness=failed"
                    + "|paths=" + report.passedPaths() + "/" + REQUIRED_PATHS.size()
                    + "|markers=" + report.passedMarkers() + "/" + REQUIRED_MARKERS.size()
                    + "|levels=" + LEVELS.size()
                    + "|errors=" + report.errorCount()
                    + "|requiresConfirmation=true"
                    + "|warnings=0");
            return;
        }
        System.out.println("zero-release-hardening-readiness=ok"
                + "|paths=" + report.passedPaths() + "/" + REQUIRED_PATHS.size()
                + "|markers=" + report.passedMarkers() + "/" + REQUIRED_MARKERS.size()
                + "|levels=" + LEVELS.size()
                + "|requiresConfirmation=true"
                + "|warnings=0");
        System.out.println();
        System.out.println("Next:");
        System.out.println("  Read docs/operations/release-checklist.zh-CN.md");
        System.out.println("  Copy docs/migrations/template.zh-CN.md for a concrete breaking release");
        System.out.println("  java scripts/ZeroUnifiedEntryVerifier.java --full-smoke");
        System.out.println("  mvn -B -ntp test");
        System.out.println("  mvn -B -ntp -Pquality verify");
        System.out.println();
        System.out.println("Note: this checks materials only; it does not execute or authorize a release.");
    }

    private static void printChecks() {
        System.out.println("zeroServer release hardening readiness checks");
        for (RequiredPath required : REQUIRED_PATHS) {
            System.out.println("[PATH] name=" + required.name() + "|value=" + required.path());
        }
        for (RequiredMarker required : REQUIRED_MARKERS) {
            System.out.println("[MARKER] name=" + required.name() + "|path=" + required.path()
                    + "|token=" + required.token());
        }
        for (ReadinessLevel level : LEVELS) {
            System.out.println("[LEVEL] name=" + level.name() + "|scope=" + level.scope());
        }
        System.out.println();
        System.out.println("zero-release-hardening-readiness-list=ok"
                + "|paths=" + REQUIRED_PATHS.size()
                + "|markers=" + REQUIRED_MARKERS.size()
                + "|levels=" + LEVELS.size()
                + "|requiresConfirmation=true");
    }

    private static void printHelp() {
        System.out.println("zeroServer release hardening readiness");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java scripts/ZeroReleaseHardeningReadiness.java");
        System.out.println("  java scripts/ZeroReleaseHardeningReadiness.java --listChecks");
        System.out.println("  java scripts/ZeroReleaseHardeningReadiness.java --help");
        System.out.println();
        System.out.println("This tool reads repository materials only; it runs no tests or release actions.");
    }

    private static RequiredPath path(final String name, final String first, final String... more) {
        return new RequiredPath(name, Path.of(first, more));
    }

    private static RequiredMarker marker(final String name, final String path, final String token) {
        return new RequiredMarker(name, Path.of(path), token);
    }

    private static String status(final boolean passed) {
        return passed ? "PASS" : "FAIL";
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

    /**
     * 必需路径。
     *
     * @param name 检查名称。
     * @param path 文件路径。
     */
    private record RequiredPath(String name, Path path) {
    }

    /**
     * 必需文本 marker。
     *
     * @param name 检查名称。
     * @param path 文件路径。
     * @param token 必须出现的固定文本。
     */
    private record RequiredMarker(String name, Path path, String token) {
    }

    /**
     * 验证或外部动作层级。
     *
     * @param name 层级名称。
     * @param scope 层级边界说明。
     */
    private record ReadinessLevel(String name, String scope) {
    }

    /**
     * 路径检查结果。
     *
     * @param required 检查定义。
     * @param present 文件是否存在。
     */
    private record PathResult(RequiredPath required, boolean present) {
    }

    /**
     * marker 检查结果。
     *
     * @param required 检查定义。
     * @param present marker 是否存在。
     */
    private record MarkerResult(RequiredMarker required, boolean present) {
    }

    /**
     * 发布硬化材料审计结果。
     */
    private static final class ReadinessReport {

        /**
         * 仓库根目录是否有效。
         */
        private boolean repositoryOk;

        /**
         * 路径检查结果；可变、有序、非空时线程不安全。
         */
        private final List<PathResult> paths = new ArrayList<>();

        /**
         * marker 检查结果；可变、有序、非空时线程不安全。
         */
        private final List<MarkerResult> markers = new ArrayList<>();

        private final boolean allowMissingEvidence;

        private ReadinessReport(final boolean allowMissingEvidence) {
            this.allowMissingEvidence = allowMissingEvidence;
        }

        private long passedPaths() {
            return paths.stream().filter(PathResult::present).count();
        }

        private long passedMarkers() {
            return markers.stream().filter(MarkerResult::present).count();
        }

        private int errorCount() {
            if (allowMissingEvidence) {
                return repositoryOk ? 0 : 1;
            }
            int repositoryError = repositoryOk ? 0 : 1;
            return repositoryError
                    + (int) (REQUIRED_PATHS.size() - passedPaths())
                    + (int) (REQUIRED_MARKERS.size() - passedMarkers());
        }

        private boolean hasErrors() {
            return errorCount() > 0;
        }
    }
}
