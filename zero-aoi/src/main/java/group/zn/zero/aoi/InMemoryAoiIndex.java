package group.zn.zero.aoi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic, single-owner in-memory AOI implementation. */
public final class InMemoryAoiIndex implements AoiIndex {
    private final Map<String, AoiEntity> entities = new HashMap<>();
    private final Map<String, Set<String>> observed = new HashMap<>();
    private final Map<String, Long> syncSequences = new HashMap<>();
    private long sceneSequence;

    @Override public Result add(final AoiEntity entity) {
        if (entities.containsKey(entity.entityId())) return new Result(Result.Status.ALREADY_EXISTS, sceneSequence);
        entities.put(entity.entityId(), entity);
        return new Result(Result.Status.APPLIED, ++sceneSequence);
    }
    @Override public Result update(final AoiEntity entity) {
        if (!entities.containsKey(entity.entityId())) return new Result(Result.Status.NOT_FOUND, sceneSequence);
        entities.put(entity.entityId(), entity);
        return new Result(Result.Status.APPLIED, ++sceneSequence);
    }
    @Override public Result remove(final String entityId) {
        if (entities.remove(entityId) == null) return new Result(Result.Status.NOT_FOUND, sceneSequence);
        return new Result(Result.Status.APPLIED, ++sceneSequence);
    }
    @Override public Set<String> visible(final Position center, final int range) {
        if (range < 0) throw new IllegalArgumentException("range < 0");
        return entities.values().stream().filter(e -> chebyshev(center, e.position()) <= range)
                .map(AoiEntity::entityId).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    @Override public List<VisibilityEvent> observe(final String observerId, final Position center, final int range) {
        Set<String> next = new HashSet<>(visible(center, range));
        Set<String> prior = observed.computeIfAbsent(observerId, ignored -> new HashSet<>());
        List<VisibilityEvent> result = new ArrayList<>();
        next.stream().filter(id -> !prior.contains(id)).sorted().forEach(id -> result.add(event(VisibilityEvent.Type.ENTER, observerId, entities.get(id))));
        next.stream().filter(prior::contains).sorted().forEach(id -> result.add(event(VisibilityEvent.Type.UPDATE, observerId, entities.get(id))));
        prior.stream().filter(id -> !next.contains(id)).sorted().forEach(id -> result.add(new VisibilityEvent(VisibilityEvent.Type.LEAVE, observerId, null, sceneSequence, nextSync(observerId))));
        prior.clear(); prior.addAll(next);
        return List.copyOf(result);
    }
    private VisibilityEvent event(final VisibilityEvent.Type type, final String observer, final AoiEntity entity) {
        return new VisibilityEvent(type, observer, entity, sceneSequence, nextSync(observer));
    }
    private long nextSync(final String observer) { return syncSequences.merge(observer, 1L, Long::sum); }
    private static int chebyshev(final Position a, final Position b) { return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.y() - b.y())); }
    @Override public long sceneSeq() { return sceneSequence; }
}
