package group.zn.zero.codegen.generator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 产物归属清单；业务实现永远不登记为可覆盖或可清理产物。 */
final class OutputManifest {
    /** 清单路径，与业务代码分开。 */
    static final String FILE = ".zero-codegen/manifest.json";
    /** 固定 schema 的 JSON 编解码器。 */
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    private OutputManifest() {
    }

    /** 加载相对路径到 SHA-256 映射；不存在返回空映射。 */
    static Map<String, String> read(final Path root) throws IOException {
        String text = OutputFiles.read(root.resolve(FILE));
        if (text == null) {
            return new LinkedHashMap<>();
        }
        try {
            Document document = JSON.fromJson(text, Document.class);
            if (document == null || document.schema() != 1 || document.files() == null
                    || document.files().entrySet().stream().anyMatch(e -> e.getKey().isBlank()
                    || e.getValue() == null || !e.getValue().matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("invalid manifest structure");
            }
            return new LinkedHashMap<>(document.files());
        } catch (RuntimeException ex) {
            throw new IOException("invalid codegen manifest: " + root.resolve(FILE), ex);
        }
    }

    /** 以排序后的路径生成确定性内容。 */
    static String render(final Map<String, String> files) {
        return JSON.toJson(new Document(1, new java.util.TreeMap<>(files))) + "\n";
    }

    /** 清单数据结构；schema 用于拒绝不受支持的存储格式。 */
    private record Document(int schema, Map<String, String> files) {
    }
}
