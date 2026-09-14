package __PACKAGE__;

import group.zn.zero.core.config.ZeroConfigLoader;
import java.util.Arrays;
import java.util.Map;

/** 所选组件的最小应用；本地默认启动，外部组件默认仅诊断。 */
public final class __APP_CLASS__ {
    private __APP_CLASS__() { }

    /** 执行诊断或一次启动 smoke；参数支持 --diagnose/--start，失败向调用者传播。 */
    public static void main(final String[] args) {
        if (args.length == 1 && args[0].equals("--diagnose")) {
            System.out.println(diagnosticSummary());
            return;
        }
        if (Arrays.stream(args).anyMatch(arg -> !arg.equals("--start"))) {
            throw new IllegalArgumentException("supported arguments: --diagnose or --start");
        }
        System.out.println(runDemo(__DEFAULT_START__ || Arrays.asList(args).contains("--start")));
    }

    /** start 为 true 时启动并关闭；否则只诊断。返回不可变摘要，每次调用独立，失败直接传播。 */
    public static String runDemo(final boolean start) {
        if (!start) {
            return "__SUMMARY_PREFIX__|" + diagnosticSummary() + "|started=false";
        }
        try (var runtime = RuntimeAssembly.create(configuration())) {
            runtime.start();
            return "__SUMMARY_PREFIX__|components=__SELECTED_COMPONENTS__|started=" + runtime.running();
        }
    }

    private static String diagnosticSummary() {
        var report = RuntimeAssembly.diagnose(configuration());
        String status = __DIAGNOSIS_STATUS__;
        return "runtime-diagnosis=" + status + "|components=__SELECTED_COMPONENTS__|missingConfigKeys="
                + __DIAGNOSIS_KEYS__;
    }

    /** 读取标准外部配置并合并默认开关；返回不可变配置，读取失败抛配置异常。 */
    public static group.zn.zero.core.config.ZeroConfig configuration() {
        return ZeroConfigLoader.loadStandard(Map.of(
                "zero.name", "__PROJECT_NAME__",
                __CONFIG_DEFAULTS__));
    }
}
