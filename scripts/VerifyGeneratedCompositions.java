import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/** Independent generated applications: actual Maven closure, absent classes and selected implementations. */
public final class VerifyGeneratedCompositions {
    private static final Path OUTPUT = Path.of("target/generated-composition-verify").toAbsolutePath().normalize();
    private static final List<Consumer> CONSUMERS = List.of(
            new Consumer("minimal", "runtime", "", Set.of("zero-core", "zero-runtime", "zero-runtime-bootstrap")),
            new Consumer("event-actor", "runtime", "event,actor", Set.of("zero-core", "zero-runtime",
                    "zero-runtime-bootstrap", "zero-actor", "zero-runtime-actor", "zero-event", "zero-runtime-event")),
            new Consumer("local-rpg", "local", "", localArtifacts()),
            new Consumer("redis", "runtime", "redis", Set.of("zero-core", "zero-runtime", "zero-runtime-bootstrap",
                    "zero-runtime-production", "zero-runtime-redis", "zero-runtime-data", "zero-runtime-cache",
                    "zero-data", "zero-data-redis", "zero-cache", "zero-protocol")),
            new Consumer("custom-actor", "local", "custom-actor", localArtifacts()));
    private static final List<String> ABSENT_CLASSES = List.of(
            "group.zn.zero.starter.LocalRuntime", "group.zn.zero.codegen.ProtocolCodegenCli",
            "freemarker.template.Configuration");
    /** Adapter 选择与实际 SDK 类型的对应关系，用于检查未选驱动确实不在 classpath。 */
    private static final Map<String, String> DRIVER_CLASSES = Map.of(
            "redis", "redis.clients.jedis.RedisClient",
            "kafka", "org.apache.kafka.clients.producer.KafkaProducer",
            "mongo", "com.mongodb.client.MongoClient",
            "postgresql", "org.postgresql.Driver",
            "nacos", "com.alibaba.nacos.api.NacosFactory");

    private VerifyGeneratedCompositions() { }

    public static void main(final String[] args) throws Exception {
        if (args.length != 0) {
            throw new IllegalArgumentException("VerifyGeneratedCompositions takes no arguments");
        }
        Files.createDirectories(OUTPUT);
        for (Consumer consumer : CONSUMERS) {
            verify(consumer);
        }
        List<String> components = supportedComponents();
        for (String component : components) {
            verify(new Consumer("component-" + component, "runtime", component, Set.of()));
        }
        verify(new Consumer("all-local", "runtime", String.join(",", components.stream()
                .filter(id -> !DRIVER_CLASSES.containsKey(id)).toList()), Set.of()));
        verify(new Consumer("mixed", "runtime", "data,redis,cache,custom-actor,discovery", Set.of()));
        verify(new Consumer("center-logic", "runtime", "rpc,kafka", Set.of()));
        verify(new Consumer("distributed", "runtime", "rpc,discovery,kafka,nacos,mongo,redis,postgresql", Set.of()));
        System.out.println("generated-compositions=ok|consumers=" + (CONSUMERS.size() + components.size() + 4));
    }

    private static List<String> supportedComponents() throws Exception {
        Path tool;
        try (var files = Files.list(Path.of("zero-codegen/target"))) {
            tool = files.filter(path -> path.getFileName().toString().matches("zero-codegen-.+-all\\.jar"))
                    .findFirst().orElseThrow();
        }
        try (var loader = new URLClassLoader(new URL[] {tool.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Set<?> ids = (Set<?>) loader.loadClass("group.zn.zero.codegen.scaffold.ScaffoldComponents")
                    .getMethod("supported").invoke(null);
            return ids.stream().map(Object::toString).toList();
        }
    }

    private static Set<String> localArtifacts() {
        return Set.of("zero-core", "zero-runtime", "zero-runtime-bootstrap", "zero-runtime-actor", "zero-actor",
                "zero-runtime-log", "zero-log", "zero-runtime-monitor", "zero-monitor", "zero-protocol",
                "zero-player", "zero-scene", "zero-game", "zero-data", "zero-cache");
    }

    private static void verify(final Consumer consumer) throws Exception {
        Path directory = OUTPUT.resolve(consumer.id());
        String packageName = "group.zn.generated." + consumer.id().replace("-", "");
        var generate = new ArrayList<>(List.of(javaCommand(), "scripts/NewLocalGame.java", "--template", consumer.template(),
                "--projectName", "verify-" + consumer.id(), "--packageName", packageName,
                "--outputDir", directory.toString(), "--force"));
        if (!consumer.components().isEmpty()) {
            generate.addAll(List.of("--components", consumer.components()));
        }
        run(generate, OUTPUT.resolve(consumer.id() + "-generate.log"));
        run(List.of(mavenCommand(), "-B", "-ntp", "-q", "-f", directory.resolve("pom.xml").toString(),
                "clean", "test", "exec:java", "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
                "-DincludeScope=runtime", "-Dmdep.outputFile=target/runtime-classpath.txt"),
                OUTPUT.resolve(consumer.id() + "-verify.log"));
        String result = readOutput(OUTPUT.resolve(consumer.id() + "-verify.log"));
        if (consumer.template().equals("runtime")) {
            Path diagnosis = OUTPUT.resolve(consumer.id() + "-diagnose.log");
            run(List.of(mavenCommand(), "-B", "-ntp", "-q", "-f", directory.resolve("pom.xml").toString(),
                    "exec:java", "-Dexec.args=--diagnose"), diagnosis);
            String expected = external(consumer) ? "incomplete" : "ok";
            require(readOutput(diagnosis).contains("runtime-diagnosis=" + expected), "unexpected diagnosis marker");
        }
        require(result.contains(consumer.template().equals("local") ? "local-game=ok" : "runtime-composition=ok"),
                "missing smoke marker: " + consumer.id());
        if (external(consumer)) {
            require(result.contains("started=false"), "external smoke must only diagnose");
        }
        List<Path> dependencies = Arrays.stream(Files.readString(directory.resolve("target/runtime-classpath.txt"))
                .trim().split(java.util.regex.Pattern.quote(File.pathSeparator))).map(Path::of).toList();
        require(consumer.artifacts().isEmpty() || frameworkArtifacts(dependencies).equals(consumer.artifacts()),
                "unexpected transitive framework dependencies for " + consumer.id() + ": " + frameworkArtifacts(dependencies));
        var urls = new ArrayList<URL>();
        urls.add(directory.resolve("target/classes").toUri().toURL());
        for (Path dependency : dependencies) {
            urls.add(dependency.toUri().toURL());
        }
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            checkClasses(loader, consumer);
            checkAssembly(loader, packageName, consumer, directory);
        }
        System.out.println("generated-consumer=ok|profile=" + consumer.id() + "|frameworkArtifacts=" + frameworkArtifacts(dependencies).size());
    }

    private static Set<String> frameworkArtifacts(final List<Path> dependencies) throws IOException {
        Set<String> artifacts = new HashSet<>();
        for (Path dependency : dependencies) {
            try (var jar = new JarFile(dependency.toFile())) {
                for (var entry : jar.stream().filter(item -> item.getName().startsWith("META-INF/maven/group.zn.zero/")
                        && item.getName().endsWith("/pom.properties")).toList()) {
                    Properties properties = new Properties();
                    try (var input = jar.getInputStream(entry)) {
                        properties.load(input);
                    }
                    artifacts.add(properties.getProperty("artifactId"));
                }
            }
        }
        return Set.copyOf(artifacts);
    }

    private static void checkClasses(final ClassLoader loader, final Consumer consumer) throws ClassNotFoundException {
        var absent = new ArrayList<>(ABSENT_CLASSES);
        Set<String> selected = Set.copyOf(Arrays.asList(consumer.components().split(",")));
        for (var driver : DRIVER_CLASSES.entrySet()) {
            if (selected.contains(driver.getKey())) {
                Class.forName(driver.getValue(), false, loader);
            } else {
                absent.add(driver.getValue());
            }
        }
        if (!selected.contains("postgresql")) {
            absent.add("com.zaxxer.hikari.HikariDataSource");
        }
        if (consumer.id().equals("minimal")) {
            absent.addAll(List.of("group.zn.zero.actor.scheduler.ActorScheduler", "group.zn.zero.event.bus.EventBus",
                    "group.zn.zero.protocol.registry.ProtocolRegistry", "group.zn.zero.log.LogAppender", "io.netty.channel.Channel"));
        }
        for (String className : absent) {
            try {
                Class.forName(className, false, loader);
                throw new IllegalStateException("unselected class is present: " + className);
            } catch (ClassNotFoundException expected) {
                // Verify against the isolated application's runtime classpath.
            }
        }
    }

    private static void checkAssembly(final ClassLoader loader, final String packageName,
                                      final Consumer consumer, final Path directory) throws Exception {
        Class<?> configType = loader.loadClass("group.zn.zero.core.config.ZeroConfig");
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(directory.resolve("config/application.properties.example"))) {
            properties.load(reader);
        }
        // 只用于无资源规划的测试配置；绝不交给真实 build/start。
        if (Arrays.asList(consumer.components().split(",")).contains("postgresql")) {
            properties.setProperty("zero.postgresql.username", "offline-test");
            properties.setProperty("zero.postgresql.password", "offline-test");
        }
        Object config = loader.loadClass("group.zn.zero.core.config.ZeroConfigLoader")
                .getMethod("fromProperties", Properties.class).invoke(null, properties);
        Class<?> assemblyType = loader.loadClass(packageName + ".RuntimeAssembly");
        if (external(consumer)) {
            for (String profile : List.of("external-test", "standalone", "production")) {
                properties.setProperty("zero.mode", profile);
                Object profileConfig = loader.loadClass("group.zn.zero.core.config.ZeroConfigLoader")
                        .getMethod("fromProperties", Properties.class).invoke(null, properties);
                Object plan = assemblyType.getMethod("plan", configType).invoke(null, profileConfig);
                verifyPlan(plan, directory);
            }
            return;
        }
        Object runtime;
        if (consumer.template().equals("local")) {
            Object sink = loader.loadClass("group.zn.zero.log.InMemoryLogSink").getConstructor().newInstance();
            runtime = assemblyType.getMethod("create", configType, loader.loadClass("group.zn.zero.log.LogSink")).invoke(null, config, sink);
        } else {
            runtime = assemblyType.getMethod("create", configType).invoke(null, config);
        }
        try (AutoCloseable owned = (AutoCloseable) runtime) {
            if (Arrays.asList(consumer.components().split(",")).contains("custom-actor")) {
                Object key = loader.loadClass("group.zn.zero.runtime.actor.ActorRuntime").getField("ACTOR_SCHEDULER").get(null);
                Object scheduler = loader.loadClass("group.zn.zero.runtime.api.GameRuntime")
                        .getMethod("require", loader.loadClass("group.zn.zero.runtime.api.ComponentKey")).invoke(runtime, key);
                require(scheduler.getClass().getName().equals("group.zn.zero.actor.scheduler.LocalActorScheduler"),
                        "custom provider was not selected");
            }
        }
    }

    /** 比较生成清单与实际规划结果，检查 provider、能力和默认替换均一致。 */
    private static void verifyPlan(final Object plan, final Path directory) throws Exception {
        List<?> components = (List<?>) plan.getClass().getMethod("components").invoke(plan);
        Set<String> providers = new HashSet<>();
        Set<String> capabilities = new HashSet<>();
        for (Object component : components) {
            Object id = component.getClass().getMethod("componentId").invoke(component);
            providers.add((String) id.getClass().getMethod("value").invoke(id));
            for (Object key : (List<?>) component.getClass().getMethod("provides").invoke(component)) {
                capabilities.add((String) key.getClass().getMethod("id").invoke(key));
            }
        }
        String manifest = Files.readString(directory.resolve("zero-scaffold.json"));
        require(providers.equals(manifestValues(manifest, "selectedProviders")), "actual providers differ from manifest");
        require(capabilities.equals(manifestValues(manifest, "runtimeCapabilities")), "actual capabilities differ from manifest");
    }

    /** 清单中的这两个数组仅含生成器控制的稳定 ID，不接受通用 JSON 或转义字符串。 */
    private static Set<String> manifestValues(final String manifest, final String name) {
        var array = java.util.regex.Pattern.compile("\"" + name + "\"\\s*:\\s*\\[([^]]*)]").matcher(manifest);
        require(array.find(), "missing manifest array: " + name);
        var entries = java.util.regex.Pattern.compile("\"([a-z0-9.-]+)\"").matcher(array.group(1));
        Set<String> values = new HashSet<>();
        while (entries.find()) {
            require(values.add(entries.group(1)), "duplicate manifest ID");
        }
        return values;
    }

    private static boolean external(final Consumer consumer) {
        return Arrays.stream(consumer.components().split(",")).anyMatch(DRIVER_CLASSES::containsKey);
    }

    private static void run(final List<String> command, final Path log) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(240, TimeUnit.SECONDS)) {
            process.descendants().toList().reversed().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IllegalStateException("command timed out; log=" + log);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("command failed; log=" + log + "\n" + readOutput(log));
        }
    }

    private static String readOutput(final Path log) throws IOException {
        return new String(Files.readAllBytes(log),
                java.nio.charset.Charset.forName(System.getProperty("native.encoding", "UTF-8")));
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString();
    }

    private static String mavenCommand() {
        return windows() ? "mvn.cmd" : "mvn";
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private record Consumer(String id, String template, String components, Set<String> artifacts) { }
}
