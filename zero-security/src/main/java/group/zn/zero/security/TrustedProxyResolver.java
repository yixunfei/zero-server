package group.zn.zero.security;

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
        if (forwardedFor.length() > 8 * 129) throw new IllegalArgumentException("forwarded address chain is too large");
        String[] hops = forwardedFor.split(",", -1);
        if (hops.length > maxForwardedHops) throw new IllegalArgumentException("forwarded address chain is not trusted");
        String source = peerAddress;
        for (int index = hops.length - 1; index >= 0; index--) {
            source = requireLiteral(hops[index].trim());
            if (!isTrusted(source)) break;
        }
        return new TrustedSourceAddress(peerAddress, source, true);
    }
    private static String requireLiteral(String source) {
        SecurityValues.require(source, "sourceAddress", 128);
        if (!source.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("forwarded address is invalid");
        if (!source.contains(":")) {
            String[] octets = source.split("\\.", -1);
            if (octets.length != 4) throw new IllegalArgumentException("forwarded IPv4 address is invalid");
            for (String octet : octets) {
                if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255)
                    throw new IllegalArgumentException("forwarded IPv4 address is invalid");
            }
        }
        try {
            java.net.InetAddress.getByName(source);
        } catch (java.net.UnknownHostException failure) {
            throw new IllegalArgumentException("forwarded address is invalid", failure);
        }
        return source;
    }

    private boolean isTrusted(String peer) { return trustedProxies.stream().anyMatch(network -> network.contains(peer)); }
    @FunctionalInterface public interface Network { boolean contains(String address); }
}
