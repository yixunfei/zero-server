import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * 仓内 Maven 生产依赖守卫：父链、全部声明 profile 并集、仓内 BOM、环与审核清单。
 * 不联网，不模拟第三方传递依赖；不支持的外部父/BOM 与关键未解析字段明确失败。
 * @author zn
 */
public final class ZeroDependencyGuard {
    /** 已审核的完整节点/边清单，只读校验，不自动接受漂移。 */
    private static final Path INVENTORY = Path.of("docs/reference/module-dependencies.tsv");
    /** Maven 属性引用。 */
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");
    /** 基础模块只允许依赖 core（core 本身不能有生产依赖）。 */
    private static final Set<String> FOUNDATIONS = Set.of("zero-runtime", "zero-event", "zero-protocol", "zero-actor", "zero-ownership");
    /** 具体驱动仅允许 adapter 与明确的装配/工具叶子持有。 */
    private static final Set<String> DRIVER_OWNERS = Set.of("zero-net", "zero-net-kcp", "zero-net-kcp-redis",
            "zero-rpc-kafka", "zero-data-mongo", "zero-data-redis", "zero-data-postgresql",
            "zero-discovery-nacos", "zero-ownership-postgresql", "zero-codegen", "zero-benchmarks",
            "zero-runtime-kafka", "zero-runtime-mongo", "zero-runtime-redis", "zero-runtime-postgresql",
            "zero-runtime-nacos", "zero-runtime-net", "zero-runtime-kcp", "zero-server-starter-production");
    /** 当前已解析 POM，按规范路径唯一。 */
    private final Map<Path, Pom> poms = new LinkedHashMap<>();
    /** 反应堆节点与声明来源。 */
    private final Map<Pom, Set<String>> modules = new LinkedHashMap<>();
    /** 仓内坐标索引，不按 artifactId 猜测外部依赖。 */
    private final Map<String, Pom> coordinates = new HashMap<>();
    /** 实际边用于消费者闭包；可选边不自动传递至下一层消费者。 */
    private final Map<Pom, Set<Dependency>> edges = new LinkedHashMap<>();
    /** 仓库根。 */
    private final Path root;

    private ZeroDependencyGuard(final Path root) { this.root = root.toAbsolutePath().normalize(); }

    /** 执行只读检查，非零异常退出；--print 输出候选清单但仍执行硬约束。 */
    public static void main(final String[] args) throws Exception {
        boolean print = args.length == 1 && args[0].equals("--print");
        if (args.length != 0 && !print) throw new IllegalArgumentException("usage: ZeroDependencyGuard [--print]");
        List<String> lines = inspect(Path.of("."));
        if (print) lines.forEach(System.out::println);
        else {
            verifyInventory(Path.of("."), lines);
            System.out.println("zero-dependency-guard=ok|modules=" + lines.stream().filter(s -> s.startsWith("module\t")).count()
                    + "|edges=" + lines.stream().filter(s -> s.startsWith("edge\t")).count());
        }
    }

    /** 供隔离夹具使用：运行同一真实模型及硬约束，返回不可变有序清单。 */
    public static List<String> inspect(final Path root) throws Exception {
        return new ZeroDependencyGuard(root).inspect();
    }

    /** 清单必须完整一致；注释与换行风格不影响校验。 */
    public static void verifyInventory(final Path root, final List<String> actual) throws Exception {
        Path path = root.resolve(INVENTORY);
        if (!Files.isRegularFile(path)) throw failure("inventory missing: " + path);
        List<String> expected = Files.readAllLines(path).stream().filter(s -> !s.isBlank() && !s.startsWith("#")).sorted().toList();
        if (!expected.equals(actual)) {
            Set<String> added = new TreeSet<>(actual);
            added.removeAll(expected);
            Set<String> removed = new TreeSet<>(expected);
            removed.removeAll(actual);
            throw failure("inventory drift; added=" + added + "; removed=" + removed);
        }
    }

    private List<String> inspect() throws Exception {
        enumerate(load(root.resolve("pom.xml"), new HashSet<>()), "root", new HashSet<>());
        for (Pom pom : modules.keySet()) {
            String key = coordinate(pom, properties(pom, Set.of()));
            if (coordinates.putIfAbsent(key, pom) != null) throw failure("duplicate module coordinate: " + key);
        }
        Set<String> lines = new TreeSet<>();
        Map<Pom, Set<Pom>> graph = new LinkedHashMap<>();
        for (Pom pom : modules.keySet()) {
            lines.add("module\t" + relative(pom.path) + "\t" + coordinate(pom, properties(pom, Set.of()))
                    + "\t" + String.join(",", new TreeSet<>(modules.get(pom))));
            graph.put(pom, new LinkedHashSet<>());
        }
        for (Pom pom : modules.keySet()) collectEdges(pom, graph, lines);
        Set<Pom> visited = new HashSet<>();
        for (Pom pom : graph.keySet()) visit(pom, graph, visited, new LinkedHashSet<>());
        checkIntegrationClosures();
        return List.copyOf(lines);
    }

    private void collectEdges(final Pom pom, final Map<Pom, Set<Pom>> graph, final Set<String> lines) throws Exception {
        for (Set<Element> active : scenarios(pom)) {
            Map<String, String> props = properties(pom, active);
            if (!coordinate(pom, props).equals(coordinate(pom, properties(pom, Set.of())))) {
                throw failure("profile changes module coordinates: " + pom.path);
            }
            Map<String, Dependency> management = management(pom, active, props, new HashSet<>());
            Map<String, Dependency> dependencies = new LinkedHashMap<>();
            for (Pom ancestor : lineage(pom)) {
                collectDeclarations(ancestor, ancestor.xml, "default", props, management, dependencies);
                for (Element profile : children(child(ancestor.xml, "profiles"), "profile")) {
                    if (active.contains(profile)) collectDeclarations(ancestor, profile, text(profile, "id"), props, management, dependencies);
                }
            }
            for (Dependency dep : dependencies.values()) {
                validateEdge(pom, dep, graph);
                edges.computeIfAbsent(pom, ignored -> new LinkedHashSet<>()).add(dep);
                lines.add("edge\t" + relative(pom.path) + "\t" + dep.group + ":" + dep.artifact + ":" + dep.version
                        + "\t" + dep.scope + "\t" + dep.optional + "\t" + dep.type + "\t" + dep.classifier
                        + "\t" + dep.source);
            }
        }
    }

    private void checkIntegrationClosures() {
        for (Pom pom : modules.keySet()) {
            String name = text(pom.xml, "artifactId");
            if (!name.startsWith("zero-runtime-") && !Set.of("zero-discovery", "zero-rpc-discovery").contains(name)) continue;
            String allowed = switch (name) {
                case "zero-runtime-kafka" -> "zero-rpc-kafka";
                case "zero-runtime-mongo" -> "zero-data-mongo";
                case "zero-runtime-redis" -> "zero-data-redis";
                case "zero-runtime-postgresql" -> "zero-data-postgresql";
                case "zero-runtime-nacos" -> "zero-discovery-nacos";
                case "zero-runtime-kcp" -> "zero-net-kcp";
                default -> "";
            };
            Set<Dependency> closure = new LinkedHashSet<>();
            closure(pom, true, new HashSet<>(), closure);
            for (Dependency dep : closure) {
                if (dep.group.equals("group.zn.zero") && (dep.artifact.startsWith("zero-server-starter")
                        || adapter(dep.artifact) && !dep.artifact.equals(allowed))) {
                    throw failure("integration closure boundary: " + name + " -> " + dep);
                }
                if (driver(dep) && !DRIVER_OWNERS.contains(name)) throw failure("neutral driver closure: " + name + " -> " + dep);
            }
        }
    }

    private void closure(final Pom pom, final boolean declaring, final Set<Pom> visited, final Set<Dependency> result) {
        if (!visited.add(pom)) return;
        for (Dependency dep : edges.getOrDefault(pom, Set.of())) {
            if (dep.scope.equals("test") || !declaring && (dep.optional.equals("true")
                    || dep.scope.equals("provided") || dep.scope.equals("system"))) continue;
            result.add(dep);
            Pom next = coordinates.get(dep.group + ":" + dep.artifact + ":" + dep.version);
            if (next != null) closure(next, false, visited, result);
        }
    }

    private void validateEdge(final Pom from, final Dependency dep, final Map<Pom, Set<Pom>> graph) {
        Pom to = coordinates.get(dep.group + ":" + dep.artifact + ":" + dep.version);
        if (dep.group.equals("group.zn.zero") && to == null) throw failure("missing internal module: " + dep);
        if (dep.scope.equals("test")) return;
        String source = resolve(text(from.xml, "artifactId"), properties(from, Set.of()));
        if (source.equals("zero-core") || FOUNDATIONS.contains(source)
                && !(dep.group.equals("group.zn.zero") && dep.artifact.equals("zero-core"))) {
            throw failure("foundation boundary: " + source + " -> " + dep);
        }
        if (driver(dep) && !DRIVER_OWNERS.contains(source)) throw failure("driver boundary: " + source + " -> " + dep);
        if (source.equals("zero-data") && dep.artifact.equals("zero-actor")) throw failure("data/actor boundary: " + dep);
        if (source.equals("zero-server-starter") && (dep.artifact.startsWith("zero-runtime-")
                && DRIVER_OWNERS.contains(dep.artifact) || adapter(dep.artifact))) {
            throw failure("local starter adapter boundary: " + dep);
        }
        if (to != null) graph.get(from).add(to); // optional 仍约束声明模块且参与生产环。
    }

    private static boolean adapter(final String name) {
        return Set.of("zero-rpc-kafka", "zero-data-mongo", "zero-data-redis", "zero-data-postgresql",
                "zero-discovery-nacos", "zero-net-kcp", "zero-net-kcp-redis", "zero-ownership-postgresql").contains(name);
    }

    private static boolean driver(final Dependency dep) {
        return dep.group.startsWith("redis.clients") || dep.group.startsWith("org.mongodb")
                || dep.group.startsWith("org.postgresql") || dep.group.startsWith("org.apache.kafka")
                || dep.group.startsWith("com.alibaba.nacos") || dep.group.startsWith("io.netty")
                || dep.group.equals("com.zaxxer");
    }

    private void visit(final Pom pom, final Map<Pom, Set<Pom>> graph, final Set<Pom> visited, final Set<Pom> visiting) {
        if (visiting.contains(pom)) throw failure("production cycle: " + visiting.stream().map(p -> relative(p.path)).toList()
                + " -> " + relative(pom.path));
        if (!visited.add(pom)) return;
        visiting.add(pom);
        for (Pom next : graph.get(pom)) visit(next, graph, visited, visiting);
        visiting.remove(pom);
    }

    private void collectDeclarations(final Pom source, final Element container, final String profile,
                                     final Map<String, String> props, final Map<String, Dependency> management,
                                     final Map<String, Dependency> dependencies) {
        for (Element item : children(child(container, "dependencies"), "dependency")) {
            Dependency dep = dependency(item, props, management, relative(source.path) + "#" + profile, false);
            dependencies.put(dep.key(), dep);
        }
    }

    private Map<String, Dependency> management(final Pom pom, final Set<Element> active,
                                              final Map<String, String> props, final Set<Pom> visiting) throws Exception {
        if (!visiting.add(pom)) throw failure("BOM import cycle: " + pom.path);
        Map<String, Dependency> result = new LinkedHashMap<>();
        for (Pom ancestor : lineage(pom)) {
            addManagement(ancestor, ancestor.xml, active, props, visiting, result);
            for (Element profile : children(child(ancestor.xml, "profiles"), "profile")) {
                if (active.contains(profile)) addManagement(ancestor, profile, active, props, visiting, result);
            }
        }
        visiting.remove(pom);
        return result;
    }

    private void addManagement(final Pom source, final Element container, final Set<Element> active,
                               final Map<String, String> props, final Set<Pom> visiting,
                               final Map<String, Dependency> result) throws Exception {
        List<Element> items = children(child(child(container, "dependencyManagement"), "dependencies"), "dependency");
        Map<String, Dependency> imports = new LinkedHashMap<>();
        Map<String, Dependency> direct = new LinkedHashMap<>();
        for (Element item : items) {
            Dependency dep = dependency(item, props, Map.of(), relative(source.path) + "#management", true);
            if (dep.scope.equals("import")) {
                Pom bom = coordinates.get(dep.group + ":" + dep.artifact + ":" + dep.version);
                if (bom == null || !dep.type.equals("pom")) throw failure("unsupported or missing BOM: " + dep);
                if (scenarios(bom).size() != 1) throw failure("profile-dependent imported BOM requires explicit model support: " + bom.path);
                management(bom, active, properties(bom, active), visiting).forEach(imports::putIfAbsent);
            } else direct.put(dep.key(), dep);
        }
        result.putAll(imports);
        result.putAll(direct);
    }

    private static Dependency dependency(final Element item, final Map<String, String> props,
                                          final Map<String, Dependency> management, final String source,
                                          final boolean managed) {
        String group = required(text(item, "groupId"), props, "dependency group");
        String artifact = required(text(item, "artifactId"), props, "dependency artifact");
        String type = resolve(or(text(item, "type"), "jar"), props);
        String classifier = resolve(text(item, "classifier"), props);
        Dependency defaults = management.get(group + ":" + artifact + ":" + type + ":" + classifier);
        String version = required(or(text(item, "version"), defaults == null ? "" : defaults.version), props, "dependency version");
        String scope = resolve(or(text(item, "scope"), defaults == null ? "compile" : defaults.scope), props);
        String optional = resolve(or(text(item, "optional"), defaults == null ? "false" : defaults.optional), props);
        if (!Set.of("compile", "runtime", "provided", "system", "test").contains(scope) && !(managed && scope.equals("import"))) {
            throw failure("unsupported dependency scope: " + scope);
        }
        if (!Set.of("true", "false").contains(optional)) throw failure("invalid optional: " + optional);
        return new Dependency(group, artifact, version, scope, optional, type, classifier, source);
    }

    private void enumerate(final Pom pom, final String origin, final Set<Pom> visiting) throws Exception {
        if (!visiting.add(pom)) throw failure("recursive module declaration: " + pom.path);
        modules.computeIfAbsent(pom, ignored -> new TreeSet<>()).add(origin);
        Map<String, String> props = properties(pom, Set.of());
        enumerateChildren(pom, pom.xml, "default", props, visiting);
        for (Element profile : children(child(pom.xml, "profiles"), "profile")) {
            enumerateChildren(pom, profile, required(text(profile, "id"), props, "profile id"),
                    properties(pom, Set.of(profile)), visiting);
        }
        visiting.remove(pom);
    }

    private void enumerateChildren(final Pom pom, final Element container, final String profile,
                                    final Map<String, String> props, final Set<Pom> visiting) throws Exception {
        for (Element module : children(child(container, "modules"), "module")) {
            String name = required(module.getTextContent().trim(), props, "module path");
            Path path = pom.path.getParent().resolve(name).normalize();
            if (!path.toString().endsWith(".xml")) path = path.resolve("pom.xml");
            enumerate(load(path, new HashSet<>()), relative(pom.path) + "#" + profile, visiting);
        }
    }

    private Pom load(final Path requested, final Set<Path> visiting) throws Exception {
        Path path = requested.toAbsolutePath().normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) throw failure("missing/outside POM: " + path);
        if (!visiting.add(path)) throw failure("parent cycle: " + path);
        if (poms.containsKey(path)) {
            visiting.remove(path);
            return poms.get(path);
        }
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element xml = factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
        Element parentElement = child(xml, "parent");
        Pom parent = null;
        if (parentElement != null) {
            String relative = child(parentElement, "relativePath") == null ? "../pom.xml" : text(parentElement, "relativePath");
            if (relative.isBlank()) throw failure("external parent unsupported: " + path);
            Path parentPath = path.getParent().resolve(relative).normalize();
            if (Files.isDirectory(parentPath)) parentPath = parentPath.resolve("pom.xml");
            parent = load(parentPath, visiting);
        }
        Pom pom = new Pom(path, xml, parent);
        if (parent != null) {
            Map<String, String> props = properties(pom, Set.of());
            String declared = required(text(parentElement, "groupId"), props, "parent group") + ":"
                    + required(text(parentElement, "artifactId"), props, "parent artifact") + ":"
                    + required(text(parentElement, "version"), props, "parent version");
            if (!declared.equals(coordinate(parent, properties(parent, Set.of())))) throw failure("parent coordinate mismatch: " + path);
        }
        poms.put(path, pom);
        visiting.remove(path);
        return pom;
    }

    private static List<Pom> lineage(final Pom pom) {
        List<Pom> result = pom.parent == null ? new ArrayList<>() : new ArrayList<>(lineage(pom.parent));
        result.add(pom);
        return result;
    }

    private static Map<String, String> properties(final Pom pom, final Set<Element> active) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Pom ancestor : lineage(pom)) {
            addProperties(result, child(ancestor.xml, "properties"));
            for (Element profile : children(child(ancestor.xml, "profiles"), "profile")) {
                if (active.contains(profile)) addProperties(result, child(profile, "properties"));
            }
        }
        String group = text(pom.xml, "groupId");
        String version = text(pom.xml, "version");
        if (pom.parent != null) {
            Map<String, String> parentProps = properties(pom.parent, active);
            group = or(group, parentProps.get("project.groupId"));
            version = or(version, parentProps.get("project.version"));
            for (String name : List.of("groupId", "artifactId", "version")) {
                result.put("project.parent." + name, parentProps.get("project." + name));
                result.put("parent." + name, parentProps.get("project." + name));
            }
        }
        result.put("project.groupId", group);
        result.put("project.version", version);
        result.put("project.artifactId", text(pom.xml, "artifactId"));
        for (String name : List.of("groupId", "artifactId", "version")) {
            result.put("pom." + name, result.get("project." + name));
        }
        return result;
    }

    private static void addProperties(final Map<String, String> props, final Element element) {
        if (element == null) return;
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element item) props.put(item.getLocalName(), item.getTextContent().trim());
        }
    }

    private static List<Set<Element>> scenarios(final Pom pom) {
        List<Set<Element>> result = new ArrayList<>();
        result.add(Set.of());
        for (Pom ancestor : lineage(pom)) {
            for (Element profile : children(child(ancestor.xml, "profiles"), "profile")) {
                if (child(profile, "dependencies") == null && child(profile, "properties") == null
                        && child(profile, "dependencyManagement") == null) continue;
                if (result.size() >= 128) throw failure("too many profile combinations: " + pom.path);
                for (Set<Element> previous : List.copyOf(result)) {
                    Set<Element> next = new HashSet<>(previous);
                    next.add(profile);
                    result.add(Set.copyOf(next));
                }
            }
        }
        return result;
    }

    private static String coordinate(final Pom pom, final Map<String, String> props) {
        return required(props.get("project.groupId"), props, "module group") + ":"
                + required(text(pom.xml, "artifactId"), props, "module artifact") + ":"
                + required(props.get("project.version"), props, "module version");
    }

    private static String required(final String value, final Map<String, String> props, final String description) {
        String result = resolve(value, props);
        if (result.isBlank()) throw failure("missing " + description);
        return result;
    }

    private static String resolve(final String value, final Map<String, String> props) {
        String current = value;
        for (int depth = 0; depth < 32; depth++) {
            var matcher = PROPERTY.matcher(current);
            if (!matcher.find()) return current;
            StringBuffer next = new StringBuffer();
            do {
                String replacement = props.get(matcher.group(1));
                if (replacement == null) throw failure("unresolved property: " + current);
                matcher.appendReplacement(next, java.util.regex.Matcher.quoteReplacement(replacement));
            } while (matcher.find());
            matcher.appendTail(next);
            current = next.toString();
        }
        throw failure("property cycle: " + value);
    }

    private String relative(final Path path) { return root.relativize(path).toString().replace('\\', '/'); }
    private static String or(final String value, final String fallback) { return value.isBlank() ? fallback : value; }
    private static String text(final Element parent, final String name) {
        Element element = child(parent, name);
        return element == null ? "" : element.getTextContent().trim();
    }
    private static Element child(final Element parent, final String name) {
        return children(parent, name).stream().findFirst().orElse(null);
    }
    private static List<Element> children(final Element parent, final String name) {
        List<Element> result = new ArrayList<>();
        if (parent != null) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element item && name.equals(item.getLocalName())) result.add(item);
            }
        }
        return result;
    }
    private static IllegalStateException failure(final String message) {
        return new IllegalStateException("zero-dependency-guard: " + message);
    }

    /** 一个仓内 POM，父链在构造前已验证无环。 */
    private record Pom(Path path, Element xml, Pom parent) { }
    /** 完整实际依赖边；管理条目只有在被声明引用时才成为图边。 */
    private record Dependency(String group, String artifact, String version, String scope, String optional,
                              String type, String classifier, String source) {
        private String key() { return group + ":" + artifact + ":" + type + ":" + classifier; }
    }
}
