package group.zn.zero.gm;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Trusted source-address policy used by the GM authorization boundary. */
@FunctionalInterface
public interface GmSourceIpPolicy {
    /** Tests one already trusted source address. */
    boolean allows(String sourceIp);

    /** Returns a policy that allows every valid non-blank source address. */
    static GmSourceIpPolicy allowAll() {
        return sourceIp -> sourceIp != null && !sourceIp.isBlank();
    }

    /** Creates an exact-address or CIDR policy with validated immutable entries. */
    static GmSourceIpPolicy of(final Collection<String> entries) {
        Objects.requireNonNull(entries, "entries");
        List<CidrBlock> blocks = entries.stream()
                .map(entry -> new CidrBlock(Objects.requireNonNull(entry, "entry")))
                .toList();
        return sourceIp -> {
            if (sourceIp == null || sourceIp.isBlank()) {
                return false;
            }
            try {
                InetAddress address = InetAddress.getByName(sourceIp);
                return blocks.stream().anyMatch(block -> block.contains(address));
            } catch (UnknownHostException exception) {
                return false;
            }
        };
    }

    /** Immutable parsed CIDR block. */
    record CidrBlock(byte[] network, int prefixLength) {
        public CidrBlock {
            Objects.requireNonNull(network, "network");
            network = network.clone();
            if (prefixLength < 0 || prefixLength > network.length * Byte.SIZE) {
                throw new IllegalArgumentException("invalid source IP prefix");
            }
        }

        CidrBlock(final String value) {
            this(parseAddress(value), parsePrefix(value));
        }

        boolean contains(final InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;
            if (!Arrays.equals(network, 0, fullBytes, candidate, 0, fullBytes)) {
                return false;
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (Byte.SIZE - remainingBits);
            return (network[fullBytes] & mask) == (candidate[fullBytes] & mask);
        }

        private static byte[] parseAddress(final String value) {
            AddressParts parts = split(value);
            requireLiteral(parts.address());
            try {
                return InetAddress.getByName(parts.address()).getAddress();
            } catch (UnknownHostException exception) {
                throw new IllegalArgumentException("invalid source IP policy", exception);
            }
        }

        private static int parsePrefix(final String value) {
            AddressParts parts = split(value);
            requireLiteral(parts.address());
            try {
                return parts.prefix() == null
                        ? InetAddress.getByName(parts.address()).getAddress().length * Byte.SIZE
                        : Integer.parseInt(parts.prefix());
            } catch (NumberFormatException | UnknownHostException exception) {
                throw new IllegalArgumentException("invalid source IP policy", exception);
            }
        }

        private static void requireLiteral(final String address) {
            if (address.contains(":")) {
                if (!address.matches("[0-9A-Fa-f:]+")) {
                    throw new IllegalArgumentException("source IP policy must use a literal address");
                }
            } else if (!address.matches("[0-9.]+")) {
                throw new IllegalArgumentException("source IP policy must use a literal address");
            }
        }

        private static AddressParts split(final String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("source IP policy entry is blank");
            }
            int slash = value.indexOf('/');
            if (slash < 0) {
                return new AddressParts(value.trim(), null);
            }
            if (slash == 0 || slash == value.length() - 1 || value.indexOf('/', slash + 1) >= 0) {
                throw new IllegalArgumentException("invalid source IP policy");
            }
            return new AddressParts(value.substring(0, slash).trim(), value.substring(slash + 1).trim());
        }

        private record AddressParts(String address, String prefix) {
        }
    }
}
