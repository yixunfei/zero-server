package group.zn.zero.examples.repository;

import org.junit.jupiter.api.Test;

class RepositoryLocalContractTest {
    @Test
    void localSourceMustSatisfyTheSameBusinessContract() {
        RepositoryContractScenario.verify("local");
    }
}
