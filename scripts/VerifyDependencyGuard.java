import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;

/** 使用隔离 POM 夹具调用真实依赖守卫；不修改工作树 POM，不联网。 @author zn */
public final class VerifyDependencyGuard {
    /** 隔离产物目录，保留供复核。 */
    private static final Path OUTPUT = Path.of("target/dependency-guard-verify", java.util.UUID.randomUUID().toString());
    /** 守卫类由当前源码即时编译，不使用旧 classes。 */
    private final Class<?> guard;
    /** 当前仓全部 POM 快照。 */
    private final Map<Path, String> poms = new java.util.LinkedHashMap<>();
    /** 用例计数。 */
    private int cases;

    private VerifyDependencyGuard(final Class<?> guard) throws Exception {
        this.guard = guard;
        for (String line : inspect(Path.of("."))) {
            if (line.startsWith("module\t")) {
                Path path = Path.of(line.split("\t")[1]);
                poms.put(path, Files.readString(path));
            }
        }
    }

    /** 执行全部正负案例，任何非预期结果非零退出。 */
    public static void main(final String[] args) throws Exception {
        Path classes = Files.createDirectories(OUTPUT.resolve("classes"));
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-d", classes.toString(),
                "scripts/ZeroDependencyGuard.java");
        if (exit != 0) throw new IllegalStateException("guard compilation failed");
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            var verifier = new VerifyDependencyGuard(loader.loadClass("ZeroDependencyGuard"));
            verifier.run();
            System.out.println("dependency-guard-fixtures=ok|cases=" + verifier.cases + "|evidence=" + OUTPUT);
        }
    }

    private void run() throws Exception {
        guard.getMethod("verifyInventory", Path.class, List.class).invoke(null, Path.of("."), inspect(Path.of(".")));
        accepted("current-parent-test-and-classifiers", path -> { });
        rejected("profile-jedis", path -> append(path, "zero-core", profile(dependency("redis.clients", "jedis", "compile", false))), "foundation boundary");
        rejected("parent-production-jedis", path -> addDependency(path, "zero-parent", dependency("redis.clients", "jedis", "compile", false)), "boundary");
        rejected("root-production-jedis", path -> append(path, "", dependencies(dependency("redis.clients", "jedis", "runtime", false))), "boundary");
        rejected("production-cycle", path -> addDependency(path, "zero-cache", dependency("group.zn.zero", "zero-player", "compile", false)), "production cycle");
        rejected("optional-cycle", path -> addDependency(path, "zero-cache", dependency("group.zn.zero", "zero-player", "compile", true)), "production cycle");
        rejected("missing-pom", path -> Files.delete(path.resolve("zero-core/pom.xml")), "missing/outside POM");
        rejected("unknown-internal-coordinate", path -> addDependency(path, "zero-cache", dependency("group.zn.zero", "zero-missing", "compile", false)), "missing internal module");
        rejected("unresolved-property", path -> addDependency(path, "zero-cache", dependency("${unknown.group}", "unknown", "compile", false)), "unresolved property");
        rejected("provided-jedis", path -> append(path, "zero-core", dependencies(dependency("redis.clients", "jedis", "provided", false))), "foundation boundary");
        rejected("system-jedis", path -> append(path, "zero-core", dependencies(dependency("redis.clients", "jedis", "system", false)
                .replace("</dependency>", "<systemPath>${project.basedir}/driver.jar</systemPath></dependency>"))), "foundation boundary");
        rejected("optional-jedis", path -> append(path, "zero-core", dependencies(dependency("redis.clients", "jedis", "compile", true))), "foundation boundary");
        accepted("management-is-not-edge", path -> append(path, "zero-core", "<dependencyManagement>"
                + dependencies(dependency("redis.clients", "jedis", "compile", false)) + "</dependencyManagement>"));
        accepted("test-cycle-not-production", path -> addDependency(path, "zero-cache", dependency("group.zn.zero", "zero-player", "test", false)));
        accepted("test-jedis", path -> append(path, "zero-core", dependencies(dependency("redis.clients", "jedis", "test", false))));
        accepted("optional-not-transitive", path -> optionalDriver(path, true));
        rejected("nonoptional-transitive", path -> optionalDriver(path, false), "neutral driver closure");
        rejected("profile-property-switch", path -> {
            addDependency(path, "zero-cache", dependency("${probe.group}", "${probe.artifact}", "compile", false));
            append(path, "zero-cache", "<properties><probe.group>example</probe.group><probe.artifact>neutral</probe.artifact></properties>"
                    + "<profiles><profile><id>driver</id><properties><probe.group>redis.clients</probe.group>"
                    + "<probe.artifact>jedis</probe.artifact></properties></profile></profiles>");
        }, "driver boundary");
        rejected("profile-management-scope", path -> {
            append(path, "zero-core", dependencies(dependency("redis.clients", "jedis", "", false))
                    + "<dependencyManagement>" + dependencies(dependency("redis.clients", "jedis", "test", false)) + "</dependencyManagement>"
                    + "<profiles><profile><id>production</id><dependencyManagement>"
                    + dependencies(dependency("redis.clients", "jedis", "compile", false))
                    + "</dependencyManagement></profile></profiles>");
        }, "foundation boundary");
        inventoryDrift("inventory-module-missing", "module\t");
        inventoryDrift("inventory-edge-missing", "edge\t");
    }

    private void optionalDriver(final Path path, final boolean optional) throws Exception {
        addDependency(path, "zero-data-redis", dependency("redis.clients", "probe-client", "compile", optional));
        addDependency(path, "zero-runtime-data", dependency("group.zn.zero", "zero-data-redis", "compile", true));
        // 测试中立 consumer -> 工具叶子 -> optional driver，不引入真实 adapter 边。
        String model = Files.readString(path.resolve("zero-runtime-data/pom.xml"));
        Files.writeString(path.resolve("zero-runtime-data/pom.xml"), model.replace("zero-data-redis", "zero-codegen"));
        addDependency(path, "zero-codegen", dependency("redis.clients", "probe-client", "compile", optional));
        // codegen 的现有 Netty 传递依赖也为本夹具设为 optional，隔离被测边。
        Path codegen = path.resolve("zero-codegen/pom.xml");
        String source = Files.readString(codegen);
        source = source.replace("<groupId>io.netty</groupId>", "<optional>true</optional><groupId>io.netty</groupId>");
        Files.writeString(codegen, source);
    }

    private void inventoryDrift(final String name, final String prefix) throws Exception {
        Path path = fixture(name);
        List<String> actual = inspect(path);
        var changed = new java.util.ArrayList<>(actual);
        changed.remove(changed.stream().filter(line -> line.startsWith(prefix)).findFirst().orElseThrow());
        Path inventory = path.resolve("docs/reference/module-dependencies.tsv");
        Files.createDirectories(inventory.getParent());
        Files.write(inventory, changed);
        try {
            guard.getMethod("verifyInventory", Path.class, List.class).invoke(null, path, actual);
            throw new AssertionError("accepted inventory drift: " + name);
        } catch (InvocationTargetException failure) {
            if (!failure.getCause().getMessage().contains("inventory drift")) throw failure;
        }
        passed(name);
    }

    private void accepted(final String name, final Mutation mutation) throws Exception {
        Path path = fixture(name);
        mutation.apply(path);
        List<String> lines = inspect(path);
        if (name.equals("current-parent-test-and-classifiers")) {
            require(lines.stream().anyMatch(s -> s.contains("junit-jupiter") && s.contains("\ttest\t") && s.contains("zero-parent/pom.xml#default")), "inherited test");
            require(lines.stream().anyMatch(s -> s.contains("linux-aarch_64")), "arm classifier");
            require(lines.stream().anyMatch(s -> s.contains("linux-x86_64")), "x86 classifier");
        }
        Files.write(path.resolve("actual.tsv"), lines);
        passed(name);
    }

    private void rejected(final String name, final Mutation mutation, final String expected) throws Exception {
        Path path = fixture(name);
        mutation.apply(path);
        try {
            inspect(path); // 故意不校验清单，证明即使清单同步更新也不能绕过硬规则。
            throw new AssertionError("accepted forbidden fixture: " + name);
        } catch (IllegalStateException failure) {
            if (!failure.getMessage().contains(expected)) throw new AssertionError(name + ": " + failure, failure);
            Files.writeString(path.resolve("rejection.txt"), failure.getMessage());
        }
        passed(name);
    }

    private Path fixture(final String name) throws Exception {
        Path path = Files.createDirectories(OUTPUT.resolve(name));
        for (var entry : poms.entrySet()) {
            Path file = path.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
        return path;
    }

    @SuppressWarnings("unchecked")
    private List<String> inspect(final Path path) throws Exception {
        try { return (List<String>) guard.getMethod("inspect", Path.class).invoke(null, path); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    private static void append(final Path root, final String module, final String xml) throws Exception {
        Path pom = root.resolve(module).resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("</project>", xml + "</project>"));
    }

    private static void addDependency(final Path root, final String module, final String xml) throws Exception {
        Path pom = root.resolve(module).resolve("pom.xml");
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(pom.toFile());
        org.w3c.dom.Node dependencies = null;
        var project = document.getDocumentElement();
        for (var node = project.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeName().equals("dependencies")) dependencies = node;
        }
        if (dependencies == null) throw new AssertionError("fixture needs dependencies: " + module);
        var addition = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        dependencies.appendChild(document.importNode(addition.getDocumentElement(), true));
        javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(
                new javax.xml.transform.dom.DOMSource(document), new javax.xml.transform.stream.StreamResult(pom.toFile()));
    }

    private static String dependency(final String group, final String artifact, final String scope, final boolean optional) {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId><version>"
                + (group.equals("group.zn.zero") ? "${project.version}" : "1") + "</version>"
                + (scope.isEmpty() ? "" : "<scope>" + scope + "</scope>") + "<optional>" + optional + "</optional></dependency>";
    }
    private static String dependencies(final String xml) { return "<dependencies>" + xml + "</dependencies>"; }
    private static String profile(final String xml) { return "<profiles><profile><id>probe</id>" + dependencies(xml) + "</profile></profiles>"; }
    private static void require(final boolean condition, final String message) { if (!condition) throw new AssertionError(message); }
    private void passed(final String name) { cases++; System.out.println("fixture=ok|" + name); }

    /** 单个隔离夹具的变异，不碰实际仓库。 */
    @FunctionalInterface private interface Mutation { void apply(Path root) throws Exception; }
}
