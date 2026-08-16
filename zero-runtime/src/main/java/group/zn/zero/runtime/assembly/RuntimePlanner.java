package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.api.BindingKey;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.config.ConfigSchema;
import group.zn.zero.runtime.config.ConfigSource;
import group.zn.zero.runtime.config.ResolvedRuntimeConfig;
import group.zn.zero.runtime.config.RuntimeConfigResolver;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan.ComponentPlan;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan.DependencyEdge;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan.DependencyEdgeKind;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan.SelectionReason;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.diagnostics.RuntimeFailurePhase;
import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 显式 selection 到最小有效组件图的确定性规划器。
 */
final class RuntimePlanner {

    private static final Comparator<BindingKey<?>> KEY_ORDER = Comparator
            .comparing((BindingKey<?> key) -> key.id())
            .thenComparing(key -> key.cardinality().name())
            .thenComparing(key -> key.type().getName());

    PlannedRuntime plan(
            final ComponentCatalog catalog,
            final RuntimeSelection selection,
            final Set<BindingKey<?>> requirements,
            final RuntimeProfile profile,
            final List<ConfigSource> configSources,
            final Optional<String> presetName) {
        PlanningInput input = new PlanningInput(
                Objects.requireNonNull(catalog, "catalog"),
                Objects.requireNonNull(selection, "selection"),
                Set.copyOf(Objects.requireNonNull(requirements, "requirements")),
                Objects.requireNonNull(profile, "profile"),
                List.copyOf(Objects.requireNonNull(configSources, "configSources")),
                Objects.requireNonNull(presetName, "presetName"));
        validateSelections(input);
        Activation activation = activateClosure(input);
        Map<BindingKey<?>, List<ComponentCatalog.RegisteredProvider>> providers = indexProviders(activation.active());
        validateBindings(activation.active(), providers);
        validateConflicts(activation.active());
        validateProfile(input.profile(), activation.active(), providers);
        ResolvedRuntimeConfig config = resolveConfig(input.configSources(), activation.active());
        List<DependencyEdge> edges = buildEdges(input.selection(), activation.active(), providers);
        List<ComponentId> topology = topologicalOrder(activation.active().keySet(), edges);
        RuntimeAssemblyPlan plan = publicPlan(input, activation, topology, edges, config);
        List<ComponentCatalog.RegisteredProvider> ordered = topology.stream()
                .map(activation.active()::get)
                .toList();
        return new PlannedRuntime(plan, ordered, config);
    }

    private void validateSelections(final PlanningInput input) {
        input.selection().singles().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ComponentKey::id)))
                .forEach(entry -> validateDecision(input.catalog(), entry.getKey(), entry.getValue()));
        input.selection().multiples().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ComponentSetKey::id)))
                .forEach(entry -> entry.getValue().values().stream()
                        .sorted(Comparator.comparing(RuntimeSelection.Decision::providerId))
                        .forEach(decision -> validateDecision(input.catalog(), entry.getKey(), decision)));
    }

    private void validateDecision(
            final ComponentCatalog catalog,
            final BindingKey<?> key,
            final RuntimeSelection.Decision decision) {
        ComponentCatalog.RegisteredProvider provider = catalog.provider(decision.providerId())
                .orElseThrow(() -> failure(
                        RuntimeErrorCode.RUNTIME_UNKNOWN_PROVIDER,
                        null,
                        "key=" + key.id()));
        if (!provider.descriptor().provides().contains(key)) {
            throw failure(
                    RuntimeErrorCode.RUNTIME_MISSING_CAPABILITY,
                    provider.descriptor().id(),
                    "key=" + key.id());
        }
    }

    private Activation activateClosure(final PlanningInput input) {
        Queue<BindingKey<?>> pending = new PriorityQueue<>(KEY_ORDER);
        pending.addAll(input.requirements());
        Set<BindingKey<?>> resolved = new HashSet<>();
        Map<ComponentId, ComponentCatalog.RegisteredProvider> active = new TreeMap<>();
        Map<ComponentId, List<SelectionReason>> reasons = new TreeMap<>();
        while (!pending.isEmpty()) {
            BindingKey<?> key = pending.remove();
            if (!resolved.add(key)) {
                continue;
            }
            for (RuntimeSelection.Decision decision : decisionsFor(input.selection(), key)) {
                ComponentCatalog.RegisteredProvider provider = input.catalog().provider(decision.providerId())
                        .orElseThrow();
                reasons.computeIfAbsent(decision.providerId(), ignored -> new ArrayList<>())
                        .add(toReason(key, decision));
                if (active.putIfAbsent(decision.providerId(), provider) == null) {
                    pending.addAll(provider.descriptor().requires());
                    provider.descriptor().optional().stream()
                            .filter(input.selection()::hasSelection)
                            .forEach(pending::add);
                }
            }
        }
        reasons.values().forEach(list -> list.sort(Comparator
                .comparing(SelectionReason::bindingId)
                .thenComparing(reason -> reason.sourceKind().name())
                .thenComparing(SelectionReason::sourceId)));
        return new Activation(active, reasons);
    }

    private List<RuntimeSelection.Decision> decisionsFor(
            final RuntimeSelection selection,
            final BindingKey<?> key) {
        if (key instanceof ComponentKey<?> single) {
            RuntimeSelection.Decision decision = selection.singles().get(single);
            if (decision == null) {
                throw failure(RuntimeErrorCode.RUNTIME_MISSING_CAPABILITY, null, "key=" + key.id());
            }
            return List.of(decision);
        }
        Map<ComponentId, RuntimeSelection.Decision> decisions = selection.multiples().get((ComponentSetKey<?>) key);
        if (decisions == null || decisions.isEmpty()) {
            throw failure(RuntimeErrorCode.RUNTIME_MISSING_CAPABILITY, null, "key=" + key.id());
        }
        return decisions.values().stream()
                .sorted(Comparator.comparing(RuntimeSelection.Decision::providerId))
                .toList();
    }

    private SelectionReason toReason(
            final BindingKey<?> key,
            final RuntimeSelection.Decision decision) {
        return new SelectionReason(
                key.id(),
                decision.providerId(),
                decision.source().kind(),
                decision.source().id(),
                Optional.ofNullable(decision.previousProviderId()));
    }

    private Map<BindingKey<?>, List<ComponentCatalog.RegisteredProvider>> indexProviders(
            final Map<ComponentId, ComponentCatalog.RegisteredProvider> active) {
        Map<BindingKey<?>, List<ComponentCatalog.RegisteredProvider>> result = new HashMap<>();
        active.values().forEach(provider -> provider.descriptor().provides().forEach(key -> result
                .computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(provider)));
        result.values().forEach(list -> list.sort(
                Comparator.comparing(provider -> provider.descriptor().id())));
        return result;
    }

    private void validateBindings(
            final Map<ComponentId, ComponentCatalog.RegisteredProvider> active,
            final Map<BindingKey<?>, List<ComponentCatalog.RegisteredProvider>> providers) {
        providers.forEach((key, candidates) -> {
            if (key instanceof ComponentKey<?> && candidates.size() > 1) {
                throw failure(RuntimeErrorCode.RUNTIME_DUPLICATE_BINDING, null, "key=" + key.id());
            }
        });
        for (ComponentCatalog.RegisteredProvider provider : active.values()) {
            for (BindingKey<?> required : provider.descriptor().requires()) {
                if (!providers.containsKey(required) || providers.get(required).isEmpty()) {
                    throw failure(
                            RuntimeErrorCode.RUNTIME_MISSING_CAPABILITY,
                            provider.descriptor().id(),
                            "key=" + required.id());
                }
            }
        }
    }

    private void validateConflicts(final Map<ComponentId, ComponentCatalog.RegisteredProvider> active) {
        for (ComponentCatalog.RegisteredProvider provider : active.values()) {
            for (ComponentId conflict : provider.descriptor().conflictsWith()) {
                if (active.containsKey(conflict)) {
                    throw failure(
                            RuntimeErrorCode.RUNTIME_COMPONENT_CONFLICT,
                            provider.descriptor().id(),
                            "conflict=" + conflict);
                }
            }
        }
    }

    private void validateProfile(
            final RuntimeProfile profile,
            final Map<ComponentId, ComponentCatalog.RegisteredProvider> active,
            final Map<BindingKey<?>, List<ComponentCatalog.RegisteredProvider>> providers) {
        for (ComponentCatalog.RegisteredProvider provider : active.values()) {
            ComponentDescriptor descriptor = provider.descriptor();
            if (!profile.allows(descriptor.kind())) {
                throw policyFailure(descriptor.id(), "kind=" + descriptor.kind().name().toLowerCase());
            }
            if (profile.requiresStartupHealth(descriptor.kind())
                    && !descriptor.healthPhases().contains(HealthPhase.STARTUP)) {
                throw policyFailure(descriptor.id(), "startup-health=missing");
            }
        }
        providers.forEach((key, candidates) -> candidates.forEach(provider -> {
            if (!profile.allowsFor(key, provider.descriptor().kind())) {
                throw policyFailure(provider.descriptor().id(), "key=" + key.id());
            }
        }));
    }

    private ResolvedRuntimeConfig resolveConfig(
            final List<ConfigSource> sources,
            final Map<ComponentId, ComponentCatalog.RegisteredProvider> active) {
        List<ConfigSchema> schemas = active.values().stream()
                .map(provider -> provider.descriptor().configSchema())
                .toList();
        return RuntimeConfigResolver.resolve(schemas, sources);
    }

    private List<DependencyEdge> buildEdges(
            final RuntimeSelection selection,
            final Map<ComponentId, ComponentCatalog.RegisteredProvider> active,
            final Map<BindingKey<?>, List<ComponentCatalog.RegisteredProvider>> providers) {
        Map<String, DependencyEdge> edges = new TreeMap<>();
        for (ComponentCatalog.RegisteredProvider consumer : active.values()) {
            addBindingEdges(edges, consumer, consumer.descriptor().requires(), providers, DependencyEdgeKind.REQUIRED);
            Set<BindingKey<?>> selectedOptional = new LinkedHashSet<>();
            consumer.descriptor().optional().stream()
                    .filter(selection::hasSelection)
                    .filter(providers::containsKey)
                    .forEach(selectedOptional::add);
            addBindingEdges(edges, consumer, selectedOptional, providers, DependencyEdgeKind.OPTIONAL);
            for (ComponentId target : consumer.descriptor().startAfter()) {
                if (active.containsKey(target)) {
                    addEdge(edges, new DependencyEdge(
                            target, consumer.descriptor().id(), DependencyEdgeKind.START_AFTER, target.value()));
                }
            }
        }
        return List.copyOf(edges.values());
    }

    private void addBindingEdges(
            final Map<String, DependencyEdge> edges,
            final ComponentCatalog.RegisteredProvider consumer,
            final Collection<BindingKey<?>> keys,
            final Map<BindingKey<?>, List<ComponentCatalog.RegisteredProvider>> providers,
            final DependencyEdgeKind kind) {
        for (BindingKey<?> key : keys) {
            for (ComponentCatalog.RegisteredProvider dependency : providers.getOrDefault(key, List.of())) {
                addEdge(edges, new DependencyEdge(
                        dependency.descriptor().id(), consumer.descriptor().id(), kind, key.id()));
            }
        }
    }

    private void addEdge(final Map<String, DependencyEdge> edges, final DependencyEdge edge) {
        String id = edge.from() + "|" + edge.to() + "|" + edge.kind() + "|" + edge.reason();
        edges.putIfAbsent(id, edge);
    }

    private List<ComponentId> topologicalOrder(
            final Set<ComponentId> componentIds,
            final List<DependencyEdge> edges) {
        Map<ComponentId, Set<ComponentId>> adjacency = new TreeMap<>();
        Map<ComponentId, Integer> indegree = new TreeMap<>();
        componentIds.forEach(id -> {
            adjacency.put(id, new TreeSet<>());
            indegree.put(id, 0);
        });
        for (DependencyEdge edge : edges) {
            if (adjacency.get(edge.from()).add(edge.to())) {
                indegree.compute(edge.to(), (id, value) -> value + 1);
            }
        }
        Queue<ComponentId> ready = new PriorityQueue<>();
        indegree.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        List<ComponentId> ordered = consumeReady(ready, adjacency, indegree);
        if (ordered.size() != componentIds.size()) {
            throw failure(
                    RuntimeErrorCode.RUNTIME_DEPENDENCY_CYCLE,
                    null,
                    "path=" + findCycle(adjacency));
        }
        return ordered;
    }

    private List<ComponentId> consumeReady(
            final Queue<ComponentId> ready,
            final Map<ComponentId, Set<ComponentId>> adjacency,
            final Map<ComponentId, Integer> indegree) {
        List<ComponentId> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            ComponentId current = ready.remove();
            ordered.add(current);
            for (ComponentId next : adjacency.get(current)) {
                int updated = indegree.compute(next, (id, value) -> value - 1);
                if (updated == 0) {
                    ready.add(next);
                }
            }
        }
        return ordered;
    }

    private String findCycle(final Map<ComponentId, Set<ComponentId>> adjacency) {
        Map<ComponentId, VisitState> states = new TreeMap<>();
        Deque<ComponentId> path = new ArrayDeque<>();
        for (ComponentId id : adjacency.keySet()) {
            Optional<List<ComponentId>> cycle = visit(id, adjacency, states, path);
            if (cycle.isPresent()) {
                return cycle.orElseThrow().stream()
                        .map(ComponentId::value)
                        .reduce((left, right) -> left + "->" + right)
                        .orElse("unknown");
            }
        }
        return "unknown";
    }

    private Optional<List<ComponentId>> visit(
            final ComponentId current,
            final Map<ComponentId, Set<ComponentId>> adjacency,
            final Map<ComponentId, VisitState> states,
            final Deque<ComponentId> path) {
        VisitState state = states.get(current);
        if (state == VisitState.DONE) {
            return Optional.empty();
        }
        if (state == VisitState.ACTIVE) {
            List<ComponentId> cycle = new ArrayList<>();
            boolean collect = false;
            for (ComponentId id : path) {
                collect = collect || id.equals(current);
                if (collect) {
                    cycle.add(id);
                }
            }
            cycle.add(current);
            return Optional.of(cycle);
        }
        states.put(current, VisitState.ACTIVE);
        path.addLast(current);
        for (ComponentId next : adjacency.get(current)) {
            Optional<List<ComponentId>> cycle = visit(next, adjacency, states, path);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        path.removeLast();
        states.put(current, VisitState.DONE);
        return Optional.empty();
    }

    private RuntimeAssemblyPlan publicPlan(
            final PlanningInput input,
            final Activation activation,
            final List<ComponentId> topology,
            final List<DependencyEdge> edges,
            final ResolvedRuntimeConfig config) {
        List<ComponentPlan> components = topology.stream()
                .map(id -> componentPlan(activation.active().get(id), activation.reasons().getOrDefault(id, List.of())))
                .toList();
        List<String> roots = input.requirements().stream().sorted(KEY_ORDER).map(BindingKey::id).toList();
        return new RuntimeAssemblyPlan(
                input.profile().name(), input.presetName(), roots, components, edges, config.metadata());
    }

    private ComponentPlan componentPlan(
            final ComponentCatalog.RegisteredProvider provider,
            final List<SelectionReason> reasons) {
        ComponentDescriptor descriptor = provider.descriptor();
        return new ComponentPlan(
                descriptor.id(),
                descriptor.kind(),
                provider.providerType(),
                provider.catalogSource(),
                List.copyOf(descriptor.provides()),
                List.copyOf(descriptor.requires()),
                List.copyOf(descriptor.optional()),
                List.copyOf(descriptor.startAfter()),
                reasons);
    }

    private RuntimeAssemblyException policyFailure(final ComponentId id, final String context) {
        return RuntimeAssemblyException.failure(
                RuntimeErrorCode.RUNTIME_POLICY_REJECTED,
                RuntimeFailurePhase.POLICY,
                id,
                context);
    }

    private RuntimeAssemblyException failure(
            final RuntimeErrorCode errorCode,
            final ComponentId componentId,
            final String context) {
        return RuntimeAssemblyException.failure(
                errorCode, RuntimeFailurePhase.PLANNING, componentId, context);
    }

    private record PlanningInput(
            ComponentCatalog catalog,
            RuntimeSelection selection,
            Set<BindingKey<?>> requirements,
            RuntimeProfile profile,
            List<ConfigSource> configSources,
            Optional<String> presetName) {
    }

    private record Activation(
            Map<ComponentId, ComponentCatalog.RegisteredProvider> active,
            Map<ComponentId, List<SelectionReason>> reasons) {
    }

    private enum VisitState {
        ACTIVE,
        DONE
    }
}
