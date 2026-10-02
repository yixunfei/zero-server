package group.zn.zero.runtime.production;

import group.zn.zero.core.config.SourcedZeroConfig;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeConfigKeys;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Shared profile validation for independent and full production compositions. */
public final class ProductionProfiles {
    private ProductionProfiles() {
    }

    public static ZeroConfig merge(final String profile, final ZeroConfig config) {
        Objects.requireNonNull(profile, "profile");
        if (!ZeroProductionRuntimeConfigKeys.MODE_PRODUCTION.equals(profile)
                && !ZeroProductionRuntimeConfigKeys.MODE_STANDALONE.equals(profile)
                && !ZeroProductionRuntimeConfigKeys.MODE_EXTERNAL_TEST.equals(profile)) {
            throw ZeroException.of(SystemErrorCode.INVALID_ARGUMENT, "unsupported production runtime profile", null);
        }
        var values = new LinkedHashMap<String, String>();
        values.put(ZeroRuntimeConfigKeys.ZERO_MODE, profile);
        values.put(ZeroRuntimeConfigKeys.ZERO_NAME, ZeroRuntimeConfigKeys.DEFAULT_NAME);
        ZeroConfig merged = SourcedZeroConfig.withDefaults(Objects.requireNonNull(config, "config"), values);
        if (!profile.equals(merged.require(ZeroRuntimeConfigKeys.ZERO_MODE))) {
            throw ZeroException.of(SystemErrorCode.INVALID_ARGUMENT,
                    "zero.mode must match the production assembly profile", null);
        }
        return merged;
    }
}
