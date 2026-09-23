package group.zn.zero.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.List;

/** Resolves forwarded addresses only when the direct peer belongs to a trusted proxy set. */
public final class TrustedProxyResolver {
    private final List<Network> trustedProxies;
    private final int maxForwardedHops;

    public TrustedProxyResolver(List<Network> trustedProxies, int maxForwardedHops) {
        this.trustedProxies = List.copyOf(java.util.Objects.requireNonNull(trustedProxies, "trustedProxies"));
        if (maxForwardedHops < 0 || maxForwardedHops > 8) throw new IllegalArgumentException("maxForwardedHops must be 0..8");
        this.maxForwardedHops = maxForwardedHops;
    }
    public TrustedSourceAddress resolve(String peerAddress, String forwardedFor) {
        SecurityValues.require(peerAddress, "peerAddress", 128);
        if (forwardedFor == null || forwardedFor.isBlank() || !isTrusted(peerAddress)) return new TrustedSourceAddress(peerAddress, peerAddress, false);
        String[] hops = forwardedFor.split(",", -1);
        if (hops.length == 0 || hops.length > maxForwardedHops) throw new IllegalArgumentException("forwarded address chain is not trusted");
        String source = hops[0].trim();
        SecurityValues.require(source, "sourceAddress", 128);
        try {
            java.net.InetAddress address = java.net.InetAddress.getByName(source);
            if (!(address instanceof Inet4Address || address instanceof Inet6Address)
                    || !source.matches("[0-9a-fA-F:.]+")) {
                throw new IllegalArgumentException("forwarded address is invalid");
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("forwarded address is invalid", e);
        }
        return new TrustedSourceAddress(peerAddress, source, true);
    }
    private boolean isTrusted(String peer) { return trustedProxies.stream().anyMatch(network -> network.contains(peer)); }
    @FunctionalInterface public interface Network { boolean contains(String address); }
}
