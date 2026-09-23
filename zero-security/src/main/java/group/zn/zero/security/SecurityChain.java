package group.zn.zero.security;

import java.util.Objects;

/** Transport-neutral security chain mode and explicit composition contract. */
public record SecurityChain(
        AuthenticationProvider authentication,
        ReplayProtection replayProtection,
        TlsMaterialProvider tlsMaterials,
        boolean tlsRequired) {
    public SecurityChain {
        if (tlsRequired && tlsMaterials == null) {
            throw new IllegalArgumentException("TLS material provider is required");
        }
    }

    /**
     * Creates a production chain and rejects the common accidental permit-all configuration.
     *
     * @param authentication application authentication provider
     * @param replayProtection application replay provider
     * @param tlsMaterials application TLS material provider
     * @return explicitly configured chain
     */
    public static SecurityChain production(
            final AuthenticationProvider authentication,
            final ReplayProtection replayProtection,
            final TlsMaterialProvider tlsMaterials) {
        return new SecurityChain(
                requireProvider(authentication, "authentication"),
                requireProvider(replayProtection, "replayProtection"),
                Objects.requireNonNull(tlsMaterials, "tlsMaterials"),
                true);
    }

    /** A deliberately non-permissive chain for tests and fail-fast defaults. */
    public static SecurityChain failClosed() {
        return new SecurityChain(AuthenticationProvider.failClosed(), ReplayProtection.failClosed(), null, false);
    }

    private static <T> T requireProvider(final T provider, final String name) {
        return Objects.requireNonNull(provider, name + " provider is required");
    }
}
