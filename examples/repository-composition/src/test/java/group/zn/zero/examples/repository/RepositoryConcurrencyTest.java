package group.zn.zero.examples.repository;

import org.junit.jupiter.api.Test;

class RepositoryConcurrencyTest {
    @Test
    void localBusinessCreditsRemainConsistentUnderContention() throws Exception {
        RepositoryWorkload.concurrent("local");
    }
}
