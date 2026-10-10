package group.zn.zero.codegen.generator.java;

import group.zn.zero.codegen.dsl.ProtocolNames;
import java.util.HashMap;
import java.util.Map;

/** 校验模板模型的单元声明、显式导入和同包引用，避免简单名称冲突。 @author zn */
final class JavaCompilationUnitNames {
    private JavaCompilationUnitNames() { }

    /** 只读预检，在输出计划应用前拒绝不可编译的单元。 */
    static void validate(final String template, final Map<String, Object> model) {
        String name = switch (template) {
            case "message.java.ftl", "enum.java.ftl" -> (String) model.get("name");
            case "codec.java.ftl" -> (String) model.get("codecName");
            case "boInterface.java.ftl" -> (String) model.get("boName");
            case "boImpl.java.ftl" -> (String) model.get("implName");
            case "payloadMarker.java.ftl" -> "ZeroGeneratedPayload";
            case "protocolIds.java.ftl" -> "ProtocolIds";
            case "protocolDefinitions.java.ftl" -> "GeneratedProtocolDefinitions";
            case "dispatcher.java.ftl" -> "GeneratedProtocolDispatcher";
            default -> throw new IllegalArgumentException("unknown Java template: " + template);
        };
        Map<String, String> symbols = new HashMap<>();
        String owner = model.get("packageName") + "." + name;
        symbols.put(name, owner);
        for (Object value : (Iterable<?>) model.get("imports")) {
            String imported = (String) value;
            String simple = imported.substring(imported.lastIndexOf('.') + 1);
            String previous = symbols.putIfAbsent(simple, imported);
            if (previous != null && !previous.equals(imported)) {
                ProtocolNames.fail("generated Java import collision in " + owner + ": " + previous + " / " + imported);
            }
        }
    }
}
