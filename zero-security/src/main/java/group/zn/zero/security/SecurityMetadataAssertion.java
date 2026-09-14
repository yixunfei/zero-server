package group.zn.zero.security;

import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Assertion signer/verifier boundary for transport metadata; key ownership stays with the application. */
public interface SecurityMetadataAssertion {
    /** Signs canonical snapshot bytes and returns an encoded signature. */
    String sign(SecurityMetadataSnapshot snapshot);

    /** Verifies a snapshot signature without trusting fields before verification. */
    boolean verify(SecurityMetadataSnapshot snapshot);

    /** Creates a SHA-256 digest assertion suitable for deterministic local tests. */
    static SecurityMetadataAssertion digest(byte[] secret) {
        byte[] key = Objects.requireNonNull(secret, "secret").clone();
        if (key.length == 0) throw new IllegalArgumentException("secret must not be empty");
        return new SecurityMetadataAssertion() {
            public String sign(final SecurityMetadataSnapshot snapshot) {
                return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(snapshot, key));
            }
            public boolean verify(final SecurityMetadataSnapshot snapshot) {
                try {
                    return MessageDigest.isEqual(digest(snapshot, key), Base64.getUrlDecoder().decode(snapshot.signature()));
                } catch (IllegalArgumentException ex) { return false; }
            }
            private byte[] digest(final SecurityMetadataSnapshot snapshot, final byte[] material) {
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    md.update(material); md.update(snapshot.canonicalBytes()); return md.digest();
                } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
            }
        };
    }

    /** Creates a signed snapshot using this assertion provider. */
    static SecurityMetadataSnapshot signed(final SecurityContext context, final String reference,
                                           final SecurityMetadataAssertion assertion) {
        SecurityMetadataSnapshot unsigned = SecurityMetadataSnapshot.from(context, reference);
        return new SecurityMetadataSnapshot(unsigned.subject(), unsigned.transport(), unsigned.peerAddress(),
                unsigned.trustedSourceAddress(), unsigned.traceId(), unsigned.correlationId(), unsigned.permissions(),
                unsigned.assertionReference(), unsigned.issuedAt(), unsigned.expiresAt(), assertion.sign(unsigned));
    }


    static SecurityMetadataVerifier verifier(final SecurityMetadataAssertion assertion) {
        Objects.requireNonNull(assertion, "assertion");
        return (snapshot, receivedAt) -> {
            if (snapshot == null || snapshot.expired(receivedAt) || !assertion.verify(snapshot)) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.completedFuture(new SecurityContext(snapshot.subject(), snapshot.issuedAt(),
                    snapshot.expiresAt(), snapshot.transport(), snapshot.peerAddress(), snapshot.trustedSourceAddress(),
                    snapshot.traceId(), snapshot.correlationId(), snapshot.permissions(), java.util.Map.of()));
        };
    }
}
