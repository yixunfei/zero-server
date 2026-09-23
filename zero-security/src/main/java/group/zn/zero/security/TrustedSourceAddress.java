package group.zn.zero.security;

/** Result of resolving a peer and optional forwarded source address. */
public record TrustedSourceAddress(String peerAddress, String sourceAddress, boolean forwarded) {
    public TrustedSourceAddress {
        SecurityValues.require(peerAddress, "peerAddress", 128);
        SecurityValues.require(sourceAddress, "sourceAddress", 128);
    }
}
