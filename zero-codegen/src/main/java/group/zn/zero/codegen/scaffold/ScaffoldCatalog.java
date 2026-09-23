package group.zn.zero.codegen.scaffold;

import group.zn.zero.runtime.capability.MavenCoordinate;
import group.zn.zero.runtime.capability.RuntimeCapabilityModel;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 七类本地脚手架及其共享 runtime 能力映射。 */
public final class ScaffoldCatalog {

    private static final List<String> LOCAL_CAPABILITIES = List.of(
            StandardRuntimeCapabilityModel.CONFIG,
            StandardRuntimeCapabilityModel.EXECUTORS,
            StandardRuntimeCapabilityModel.LOG_APPENDER,
            StandardRuntimeCapabilityModel.ACTOR_SCHEDULER,
            StandardRuntimeCapabilityModel.MONITOR_RUNTIME);

    private static final List<ScaffoldDependency> DIRECT_DEPENDENCIES = List.of(
            new ScaffoldDependency(MavenCoordinate.zero("zero-protocol"), ""));

    private final RuntimeCapabilityModel capabilityModel;
    private final Map<String, ScaffoldTemplate> templates;

    private ScaffoldCatalog(final RuntimeCapabilityModel capabilityModel) {
        this.capabilityModel = Objects.requireNonNull(capabilityModel, "capabilityModel");
        Map<String, ScaffoldTemplate> entries = new LinkedHashMap<>();
        standardTemplates().forEach(template -> {
            if (entries.putIfAbsent(template.id(), template) != null) {
                throw new IllegalStateException("duplicate scaffold template: " + template.id());
            }
            template.capabilityIds().forEach(capabilityId -> capabilityModel.capability(capabilityId)
                    .orElseThrow(() -> new IllegalStateException("unknown scaffold capability: " + capabilityId)));
        });
        templates = Collections.unmodifiableMap(entries);
    }

    public static ScaffoldCatalog standard() {
        return new ScaffoldCatalog(StandardRuntimeCapabilityModel.instance());
    }

    public RuntimeCapabilityModel capabilityModel() {
        return capabilityModel;
    }

    public List<ScaffoldTemplate> templates() {
        return List.copyOf(templates.values());
    }

    public Optional<ScaffoldTemplate> find(final String templateId) {
        return Optional.ofNullable(templates.get(Objects.requireNonNull(templateId, "templateId").toLowerCase()));
    }

    public ScaffoldTemplate require(final String templateId) {
        return find(templateId).orElseThrow(() -> new IllegalArgumentException(
                "unsupported template: " + templateId + " (supported: " + String.join(", ", templates.keySet()) + ')'));
    }

    private static Collection<ScaffoldTemplate> standardTemplates() {
        return List.of(
                new ScaffoldTemplate("runtime", "runtime-scaffold", "", "",
                        "minimal runtime with selected components", List.of("runtime", "minimal", "components"),
                        "explicit component assembly and replaceable providers", "runtime-composition=ok",
                        "external service startup and deployment verification",
                        List.of(StandardRuntimeCapabilityModel.CONFIG, StandardRuntimeCapabilityModel.EXECUTORS),
                        List.of()),
                template("local", "local-game-scaffold", "Game.si.tpl", "src/main/protocol/Game.si",
                        "player and scene local prototype",
                        List.of("local", "rpg", "player", "scene", "login", "move", "prototype",
                                "本地", "玩家", "场景"),
                        "RPG or common online game local prototype with login, player and scene flow",
                        "local-game=ok",
                        "auth, reconnect, durable online state and production persistence"),
                template("room", "room-game-scaffold", "Room.si.tpl", "src/main/protocol/Room.si",
                        "room and match local prototype",
                        List.of("room", "match", "battle", "pvp", "ready", "frame", "房间", "匹配", "对战", "小局"),
                        "room, match, ready and small battle state serialized by actor lane",
                        "room-game=ok",
                        "matchmaking, broadcast, reconnect, spectator, settlement and cross-server room"),
                template("scene-sync", "scene-sync-scaffold", "SceneSync.si.tpl",
                        "src/main/protocol/SceneSync.si", "scene state sync and simple AOI local prototype",
                        List.of("scene", "sync", "aoi", "visibility", "entity", "rpg", "mmo", "场景", "同步", "可见性"),
                        "scene entity state sync and small-scale AOI visibility query",
                        "scene-sync=ok",
                        "production AOI index, broadcast, delta compression, snapshot protocol and migration"),
                template("frame-sync", "frame-sync-scaffold", "FrameSync.si.tpl",
                        "src/main/protocol/FrameSync.si", "frame sync lockstep local prototype",
                        List.of("frame", "lockstep", "input", "snapshot", "deterministic", "帧同步", "输入", "快照"),
                        "lockstep match input collection, frame advance and snapshot summary",
                        "frame-sync=ok",
                        "clock model, input recovery, rollback, spectator, reliable broadcast and anti-cheat"),
                template("npc-tick", "npc-tick-scaffold", "NpcTick.si.tpl",
                        "src/main/protocol/NpcTick.si", "AI NPC lifecycle and tick local prototype",
                        List.of("npc", "ai", "tick", "zone", "behavior", "spawn", "怪物", "行为", "调度"),
                        "NPC spawn, behavior switch, zone tick and NPC state query",
                        "npc-tick=ok",
                        "behavior tree, pathfinding, combat AI, tick budget, backpressure and migration"),
                template("ranking-season", "ranking-season-scaffold", "RankingSeason.si.tpl",
                        "src/main/protocol/RankingSeason.si", "ranking and season local prototype",
                        List.of("ranking", "rank", "season", "score", "top", "leaderboard", "排行", "赛季", "积分"),
                        "ranking score submit, top query, player rank query and season reset",
                        "ranking-season=ok",
                        "Redis sorted set, cross-server ranking, settlement, reward and idempotency"),
                template("world-shard", "world-shard-scaffold", "WorldShard.si.tpl",
                        "src/main/protocol/WorldShard.si", "open world shard and migration local prototype",
                        List.of("world", "shard", "openworld", "migration", "transfer", "partition",
                                "开放世界", "分片", "迁移"),
                        "open world shard state, entity movement, shard transfer and entity query",
                        "world-shard=ok",
                        "cross-process migration, state handoff, cross-server broadcast, AOI stitching and consistency"));
    }

    private static ScaffoldTemplate template(
            final String id,
            final String directory,
            final String protocolTemplate,
            final String protocolOutput,
            final String description,
            final List<String> keywords,
            final String useCase,
            final String summaryPrefix,
            final String productionGap) {
        return new ScaffoldTemplate(
                id,
                directory,
                protocolTemplate,
                protocolOutput,
                description,
                keywords,
                useCase,
                summaryPrefix,
                productionGap,
                LOCAL_CAPABILITIES,
                id.equals("local") ? List.of(
                        DIRECT_DEPENDENCIES.getFirst(),
                        new ScaffoldDependency(MavenCoordinate.zero("zero-player"), ""),
                        new ScaffoldDependency(MavenCoordinate.zero("zero-scene"), "")) : DIRECT_DEPENDENCIES);
    }
}
