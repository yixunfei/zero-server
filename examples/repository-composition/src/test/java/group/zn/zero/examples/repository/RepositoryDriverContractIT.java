package group.zn.zero.examples.repository;

import org.junit.jupiter.api.Test;

class RepositoryDriverContractIT {
    @Test
    void selectedDriverMustSatisfyTheBusinessContract() {
        String backend = System.getProperty("zero.repository.backend", "");
        if (!java.util.Set.of("mongo", "postgresql", "redis").contains(backend)) {
            throw new IllegalArgumentException("set zero.repository.backend to mongo, postgresql or redis");
        }
        RepositoryContractScenario.verify(backend);
    }
}
