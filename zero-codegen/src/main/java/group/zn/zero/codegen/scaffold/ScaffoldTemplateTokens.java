package group.zn.zero.codegen.scaffold;

import java.util.Map;
import java.util.regex.Pattern;

/** 只扫描原始模板一次，替换值永不作为模板再次解释。 @author zn */
final class ScaffoldTemplateTokens {
    /** 模板 token 的固定语法。 */
    private static final Pattern TOKEN = Pattern.compile("__[A-Z][A-Z0-9_]*?__");

    private ScaffoldTemplateTokens() { }

    static String render(final String template, final Map<String, String> values) {
        var matcher = TOKEN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group());
            if (value == null) throw new IllegalArgumentException("unknown scaffold placeholder: " + matcher.group());
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString().replace("\r\n", "\n").replace("\r", "\n");
    }
}
