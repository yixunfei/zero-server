package group.zn.zero.examples.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Fault injection is restricted to the disposable Compose project created by the runner. */
final class IsolatedDatabase implements AutoCloseable {
    private final String service;
    private final List<String> command;
    private final String publishedPort;
    private final String containerPort;
    private boolean stopped;

    IsolatedDatabase(final String service) throws IOException, InterruptedException {
        if (!Set.of("mongo", "postgresql", "redis").contains(service)) {
            throw new IllegalArgumentException("unknown database service");
        }
        String project = System.getenv("ZERO_REPOSITORY_COMPOSE_PROJECT");
        String file = System.getenv("ZERO_REPOSITORY_COMPOSE_FILE");
        if (project == null || !project.matches("zero-repository-[0-9a-f]{32}")
                || file == null || !Files.isRegularFile(Path.of(file))) {
            throw new IllegalArgumentException("resilience tests require the isolated Compose runner");
        }
        this.service = service;
        containerPort = switch (service) {
            case "mongo" -> "27017";
            case "postgresql" -> "5432";
            default -> "6379";
        };
        command = List.of("docker", "compose", "-f", file, "-p", project);
        String container = compose("ps", "-q", service).trim();
        if (!container.matches("[0-9a-f]{12,64}")) {
            throw new IllegalStateException("expected one running isolated container for " + service);
        }
        String owned = run(List.of("docker", "container", "ls", "-aq", "--no-trunc",
                "--filter", "id=" + container, "--filter", "label=com.docker.compose.project=" + project,
                "--filter", "label=com.docker.compose.service=" + service)).trim();
        if (!container.equals(owned)) { throw new IllegalStateException("Compose ownership mismatch"); }
        publishedPort = compose("port", service, containerPort).trim();
    }

    void stop() throws IOException, InterruptedException {
        stopped = true;
        compose("stop", "--timeout", "10", service);
    }

    void start() throws IOException, InterruptedException {
        compose("start", "--wait", "--wait-timeout", "120", service);
        stopped = false;
        if (!publishedPort.equals(compose("port", service, containerPort).trim())) {
            throw new IllegalStateException("database endpoint changed during restart");
        }
    }

    @Override
    public void close() throws IOException, InterruptedException {
        if (stopped) { start(); }
    }

    private String compose(final String... arguments) throws IOException, InterruptedException {
        var argumentsList = new ArrayList<>(command);
        argumentsList.addAll(List.of(arguments));
        return run(argumentsList);
    }

    private static String run(final List<String> arguments) throws IOException, InterruptedException {
        Path output = Files.createTempFile("zero-repository-docker-", ".log");
        try {
            Process process = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                if (!process.waitFor(150, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Docker command timed out: " + arguments.get(1));
                }
                String result = Files.readString(output);
                if (process.exitValue() != 0) { throw new IllegalStateException("Docker command failed: " + result); }
                return result;
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
