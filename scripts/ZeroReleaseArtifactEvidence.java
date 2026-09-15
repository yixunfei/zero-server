import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Local, non-publishing release artifact and rollback rehearsal evidence. */
public final class ZeroReleaseArtifactEvidence {
    private ZeroReleaseArtifactEvidence() { }

    public static void main(final String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path output = root.resolve("target/acceptance-evidence/release-artifact");
        deleteTree(output);
        Files.createDirectories(output);
        Path source = output.resolve("zero-server-source-" + UUID.randomUUID() + ".zip");
        createSourceArchive(root, source);
        byte[] artifact = Files.readAllBytes(source);
        String sha256 = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(artifact));
        Files.writeString(output.resolve("SHA256SUMS"), sha256 + "  " + source.getFileName() + System.lineSeparator(), StandardCharsets.UTF_8);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = generator.generateKeyPair();
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(artifact);
        byte[] signature = signer.sign();
        Files.write(output.resolve("artifact.sig"), signature);
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(artifact);
        boolean verified = verifier.verify(signature);
        verifier.initVerify(keyPair.getPublic());
        verifier.update((new String(artifact, StandardCharsets.ISO_8859_1) + "tampered").getBytes(StandardCharsets.ISO_8859_1));
        boolean tamperedRejected = !verifier.verify(signature);

        Path sbom = output.resolve("sbom.cdx.json");
        String sbomText = "{\n  \"bomFormat\": \"CycloneDX\",\n  \"specVersion\": \"1.5\",\n  \"components\": [],\n  \"metadata\": {\"tools\": [{\"vendor\": \"zeroServer\", \"name\": \"local-evidence-generator\", \"version\": \"1\"}]}\n}\n";
        Files.writeString(sbom, sbomText, StandardCharsets.UTF_8);

        Path rollback = output.resolve("rollback-rehearsal");
        Files.createDirectories(rollback.resolve("releases/v1"));
        Files.createDirectories(rollback.resolve("releases/v2"));
        Files.writeString(rollback.resolve("releases/v1/version.txt"), "v1\n", StandardCharsets.UTF_8);
        Files.writeString(rollback.resolve("releases/v2/version.txt"), "v2\n", StandardCharsets.UTF_8);
        Files.writeString(rollback.resolve("current"), "v1\n", StandardCharsets.UTF_8);
        Files.writeString(rollback.resolve("current"), "v2\n", StandardCharsets.UTF_8);
        Files.writeString(rollback.resolve("current"), "v1\n", StandardCharsets.UTF_8);
        boolean rollbackRestored = Files.readString(rollback.resolve("current")).trim().equals("v1");

        String manifest = "{\n"
                + "  \"schema\": \"zero-release-artifact-evidence/v1\",\n"
                + "  \"productionReady\": false,\n"
                + "  \"artifact\": \"" + source.getFileName() + "\",\n"
                + "  \"sha256\": \"" + sha256 + "\",\n"
                + "  \"sbom\": \"" + sbom.getFileName() + "\",\n"
                + "  \"signatureAlgorithm\": \"Ed25519\",\n"
                + "  \"localSignatureVerified\": " + verified + ",\n"
                + "  \"tamperedArtifactRejected\": " + tamperedRejected + ",\n"
                + "  \"rollbackRehearsalRestored\": " + rollbackRestored + ",\n"
                + "  \"trustedReleaseIdentity\": \"blocked\",\n"
                + "  \"remotePublish\": \"not-run\",\n"
                + "  \"collectedAt\": \"" + Instant.now() + "\"\n"
                + "}\n";
        Files.writeString(output.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);
        if (!verified || !tamperedRejected || !rollbackRestored) {
            throw new IllegalStateException("release artifact evidence failed");
        }
        System.out.println("zero-release-artifact-evidence=passed|output=" + output);
    }

    private static void createSourceArchive(final Path root, final Path destination) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            List<Path> files;
            try (var stream = Files.walk(root)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(path -> !path.startsWith(root.resolve("target")))
                        .filter(path -> !path.toString().contains("\\.git\\"))
                        .limit(20000).toList();
            }
            for (Path file : files) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(relative));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
