package __PACKAGE__;

import group.zn.zero.core.config.ZeroConfigLoader;
import java.util.Arrays;
import java.util.Map;

public final class __APP_CLASS__ {
    private __APP_CLASS__() { }

    public static void main(final String[] args) {
        if (args.length == 1 && args[0].equals("--diagnose")) {
            System.out.println("runtime-diagnosis=ok|report=" + RuntimeAssembly.diagnose(configuration()));
            return;
        }
        if (Arrays.stream(args).anyMatch(arg -> !arg.equals("--start"))) {
            throw new IllegalArgumentException("supported arguments: --diagnose or --start");
        }
        System.out.println(runDemo(__DEFAULT_START__ || Arrays.asList(args).contains("--start")));
    }

    public static String runDemo(final boolean start) {
        try (var runtime = RuntimeAssembly.create(configuration())) {
            if (start) {
                runtime.start();
            }
            return "__SUMMARY_PREFIX__|components=__SELECTED_COMPONENTS__|started=" + runtime.running();
        }
    }

    public static group.zn.zero.core.config.ZeroConfig configuration() {
        return ZeroConfigLoader.loadStandard(Map.of(
                "zero.name", "__PROJECT_NAME__",
                __CONFIG_DEFAULTS__));
    }
}
