package group.zn.zero.codegen;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** JSON 工程配置；路径相对于配置文件，CLI 与 GUI 共用，无全局可变状态。 */
public final class CodegenProjectConfig {
    /** 只属于一次执行的选项不能保存在工程文件内。 */
    private static final Set<String> COMMANDS = Set.of("config", "gui", "help", "h", "mode", "json");

    private CodegenProjectConfig() {
    }

    /**
     * 读取配置并解析为工具参数，拒绝重复键和未知键。
     * @param path UTF-8 JSON 配置路径。
     * @return 可变、有序参数映射；输入数组以换行分隔，路径为绝对路径。
     * @throws IllegalArgumentException 配置非法或不可读，不修改文件。
     */
    public static Map<String, String> read(final Path path) {
        Path base = path.toAbsolutePath().normalize().getParent();
        Map<String, String> values = new LinkedHashMap<>();
        try (Reader input = Files.newBufferedReader(path); JsonReader reader = new JsonReader(input)) {
            reader.setStrictness(com.google.gson.Strictness.STRICT);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!ProtocolCodegenCli.isKnownOption("--" + name) || COMMANDS.contains(name)) {
                    throw new IllegalArgumentException("unknown project setting: " + name);
                }
                String value = readValue(reader, name, base);
                if (values.putIfAbsent("--" + name, value) != null) {
                    throw new IllegalArgumentException("duplicate project setting: " + name);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("trailing content in project config");
            }
            values.putIfAbsent("--out", base.resolve("target/generated-sources/zero-codegen").toString());
            return values;
        } catch (IOException | IllegalStateException ex) {
            throw new IllegalArgumentException("invalid project config " + path + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * 将 GUI 当前设置保存为可移植配置；不会生成协议文件。
     * @param path 目标配置文件。
     * @param values 参数映射；路径可相对于当前目录，输入以换行分隔。
     * @throws IllegalArgumentException 写入失败。
     */
    public static void write(final Path path, final Map<String, String> values) {
        Path base = path.toAbsolutePath().normalize().getParent();
        JsonObject json = new JsonObject();
        values.forEach((key, value) -> {
            String name = key.substring(2);
            if ("input".equals(name)) {
                JsonArray inputs = new JsonArray();
                for (String item : value.split("\\R")) {
                    inputs.add(portablePath(base, item));
                }
                json.add(name, inputs);
            } else {
                json.addProperty(name, isPath(name) ? portablePath(base, value) : value);
            }
        });
        try {
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n");
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to save project config: " + path, ex);
        }
    }

    private static String readValue(final JsonReader reader, final String name, final Path base) throws IOException {
        if ("input".equals(name)) {
            reader.beginArray();
            StringBuilder inputs = new StringBuilder();
            while (reader.hasNext()) {
                if (!inputs.isEmpty()) {
                    inputs.append('\n');
                }
                inputs.append(resolve(base, reader.nextString()));
            }
            reader.endArray();
            return inputs.toString();
        }
        String value = reader.peek() == JsonToken.BOOLEAN ? Boolean.toString(reader.nextBoolean()) : reader.nextString();
        return isPath(name) ? resolve(base, value) : value;
    }

    private static boolean isPath(final String name) {
        return name.startsWith("out") || "protoId".equals(name);
    }

    private static String resolve(final Path base, final String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        return base.resolve(value).normalize().toString();
    }

    private static String portablePath(final Path base, final String value) {
        Path absolute = Path.of(value).toAbsolutePath().normalize();
        String result = base.getRoot().equals(absolute.getRoot()) ? base.relativize(absolute).toString() : absolute.toString();
        return result.isEmpty() ? "." : result.replace('\\', '/');
    }
}
