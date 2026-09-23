package group.zn.zero.examples.repository;

import group.zn.zero.data.repository.CrudRepository;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Business code has no reference to a runtime, storage role, connection or adapter. */
public final class BalanceService {
    private final CrudRepository<String, Balance> balances;

    public BalanceService(final CrudRepository<String, Balance> balances) {
        this.balances = Objects.requireNonNull(balances, "balances");
    }

    public CompletionStage<Balance> credit(final String id, final long amount) {
        return balances.findById(id).thenCompose(existing -> {
            Balance previous = existing.orElse(new Balance(id, 0, 0));
            Balance next = new Balance(id, previous.version(), Math.addExact(previous.amount(), amount));
            return balances.save(next).thenCompose(ignored -> balances.findById(id))
                    .thenApply(java.util.Optional::orElseThrow);
        });
    }
}
