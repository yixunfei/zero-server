package group.zn.zero.examples.repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

class RepositoryResilienceIT {
    private static final List<String> BACKENDS = List.of("local", "mongo", "postgresql", "redis");

    @Test
    void recoveryThenConcurrencyThenSustainedOperation() throws Exception {
        int seconds = Integer.parseInt(System.getProperty("zero.repository.soak-seconds", "300"));
        if (seconds < 1 || seconds > 86_400) { throw new IllegalArgumentException("soak seconds must be in 1..86400"); }
        for (String backend : BACKENDS.subList(1, BACKENDS.size())) { RepositoryRecoveryScenario.verify(backend); }
        for (String backend : BACKENDS) { RepositoryWorkload.concurrent(backend); }
        var tasks = new ArrayList<Callable<Void>>();
        for (String backend : BACKENDS) {
            tasks.add(() -> {
                try { RepositoryWorkload.soak(backend, seconds); }
                catch (Exception failure) { throw new IllegalStateException("soak backend=" + backend, failure); }
                return null;
            });
        }
        RepositoryWorkload.runAll(tasks, Duration.ofSeconds(seconds + 180L));
    }
}
