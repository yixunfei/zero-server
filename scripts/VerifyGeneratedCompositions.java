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
            "freemarker.template.Configuration", "org.apache.kafka.clients.producer.KafkaProducer",
            "com.mongodb.client.MongoClient", "org.postgresql.Driver", "com.zaxxer.hikari.HikariDataSource",
            "com.alibaba.nacos.api.NacosFactory");

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
                .filter(id -> !id.equals("redis")).toList()), Set.of()));
        verify(new Consumer("mixed", "runtime", "data,redis,cache,custom-actor,discovery", Set.of()));
        System.out.println("generated-compositions=ok|consumers=" + (CONSUMERS.size() + components.size() + 2));
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
            require(readOutput(diagnosis).contains("runtime-diagnosis=ok"), "missing diagnosis marker");
        }
        require(result.contains(consumer.template().equals("local") ? "local-game=ok" : "runtime-composition=ok"),
                "missing smoke marker: " + consumer.id());
        if (usesRedis(consumer)) {
            require(result.contains("started=false"), "Redis smoke must not start a service");
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
            checkAssembly(loader, packageName, consumer);
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
        if (!usesRedis(consumer)) {
            absent.add("redis.clients.jedis.RedisClient");
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
        if (usesRedis(consumer)) {
            Class.forName("redis.clients.jedis.RedisClient", false, loader);
        }
    }

    private static void checkAssembly(final ClassLoader loader, final String packageName, final Consumer consumer) throws Exception {
        Class<?> configType = loader.loadClass("group.zn.zero.core.config.ZeroConfig");
        Object config = loader.loadClass("group.zn.zero.core.config.MapZeroConfig").getConstructor(Map.class).newInstance(Map.of(
                "zero.name", consumer.id(), "zero.adapter.data.redis.enabled", "true", "zero.redis.uri", "redis://127.0.0.1:1"));
        Class<?> assemblyType = loader.loadClass(packageName + ".RuntimeAssembly");
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

    private static boolean usesRedis(final Consumer consumer) {
        return Arrays.asList(consumer.components().split(",")).contains("redis");
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
