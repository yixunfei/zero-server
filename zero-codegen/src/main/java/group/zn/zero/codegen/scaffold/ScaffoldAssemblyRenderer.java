package group.zn.zero.codegen.scaffold;

/** Renders the application composition root from allowlisted component expressions. */
final class ScaffoldAssemblyRenderer {
    String render(final ProjectScaffoldRequest request, final ScaffoldComponents.Selection selection) {
        boolean business = request.template().generatesProtocol();
        String parameters = "final ZeroConfig config" + (business ? ", final group.zn.zero.log.LogSink sink" : "");
        String arguments = business ? "config, sink" : "config";
        String builderType = selection.external()
                ? "group.zn.zero.runtime.production.ProductionAssembly" : "RuntimeComposition";
        String reportType = selection.external()
                ? "group.zn.zero.runtime.production.ZeroProductionAssemblyReport"
                : "group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan";
        StringBuilder source = new StringBuilder("package " + request.packageName() + ";\n\n");
        source.append("import group.zn.zero.core.config.ZeroConfig;\n")
                .append("import group.zn.zero.runtime.api.GameRuntime;\n")
                .append("import group.zn.zero.runtime.assembly.RuntimeComposition;\n")
                .append("import group.zn.zero.runtime.assembly.RuntimeProfile;\n")
                .append("import group.zn.zero.runtime.bootstrap.RuntimeBasics;\n")
                .append("import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;\n\n")
                .append("public final class RuntimeAssembly {\n")
                .append("    private RuntimeAssembly() { }\n\n")
                .append("    public static GameRuntime create(").append(parameters).append(") {\n")
                .append("        return composition(").append(arguments).append(").build();\n    }\n\n")
                .append("    public static ").append(reportType).append(" diagnose(").append(parameters).append(") {\n")
                .append("        return composition(").append(arguments).append(").diagnose();\n    }\n\n")
                .append("    private static ").append(builderType).append(" composition(").append(parameters).append(") {\n");
        appendBuilder(source, business, selection.external());
        for (String component : selection.components()) {
            String expression = ScaffoldComponents.installExpression(component);
            if (!expression.isEmpty()) {
                if (component.equals("log") && business) {
                    expression = "group.zn.zero.runtime.log.LogRuntime.module(() -> sink)";
                }
                source.append("        assembly.install(").append(expression).append(");\n");
            }
        }
        if (selection.components().contains("data") || selection.components().contains("redis")) {
            String backend = selection.external() ? "redis" : "local";
            source.append("        assembly.install(group.zn.zero.runtime.data.DataRuntime.repositories(\n")
                    .append("                java.util.Map.of(\"main\", \"").append(backend).append("\")));\n");
        }
        if (selection.components().contains("custom-actor")) {
            source.append(selection.external()
                    ? "        assembly.configure(RuntimeAssembly::customizeActor);\n"
                    : "        customizeActor(assembly);\n");
        }
        source.append("        return assembly;\n    }\n");
        if (selection.components().contains("custom-actor")) {
            source.append(customActor());
        }
        return source.append("}\n").toString();
    }

    private void appendBuilder(final StringBuilder source, final boolean business, final boolean external) {
        if (external) {
            source.append("        var assembly = group.zn.zero.runtime.production.ProductionAssembly.builder(\n")
                    .append("                \"external-test\", config, ZeroRuntimeExecutors.direct());\n");
            if (business) {
                source.append("        assembly.base(RuntimeBasics.module(config, () ->\n")
                        .append("                ZeroRuntimeExecutors.localPrototype(config.getOrDefault(\"zero.name\", \"game\"), 2)));\n");
            }
        } else if (business) {
            source.append("        var assembly = RuntimeComposition.builder(RuntimeProfile.local())\n")
                    .append("                .install(RuntimeBasics.module(config, () ->\n")
                    .append("                        ZeroRuntimeExecutors.localPrototype(config.getOrDefault(\"zero.name\", \"game\"), 2)));\n");
        } else {
            source.append("        var assembly = RuntimeBasics.builder(config);\n");
        }
    }

    private String customActor() {
        return """

                    private static void customizeActor(final RuntimeComposition assembly) {
                        var id = group.zn.zero.runtime.api.ComponentId.of("application.actor");
                        var key = group.zn.zero.runtime.actor.ActorRuntime.ACTOR_SCHEDULER;
                        assembly.register(group.zn.zero.runtime.spi.RuntimeProviders.create(
                                group.zn.zero.runtime.spi.ComponentDescriptor.builder(id)
                                        .provide(key).kind(group.zn.zero.runtime.spi.ComponentKind.BUSINESS).build(),
                                context -> group.zn.zero.runtime.spi.ComponentContribution.builder().bind(key,
                                        new group.zn.zero.actor.scheduler.LocalActorScheduler()).build()));
                        assembly.override(key, id);
                    }
                """;
    }

    String configExample(final ProjectScaffoldRequest request, final ScaffoldComponents.Selection selection) {
        String properties = "zero.name=" + request.projectName() + "\nzero.mode="
                + (selection.external() ? "external-test" : "local") + "\n";
        if (selection.external()) {
            properties += "zero.adapter.data.redis.enabled=true\nzero.redis.uri=redis://127.0.0.1:6379\n";
        }
        return properties;
    }
}
