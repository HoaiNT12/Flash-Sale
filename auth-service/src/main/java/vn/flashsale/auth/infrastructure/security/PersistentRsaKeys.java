package vn.flashsale.auth.infrastructure.security;

import com.nimbusds.jose.jwk.RSAKey;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Set;

/** Signing identity survives container restarts. Key loss is a deliberate administrative operation. */
public final class PersistentRsaKeys {
    private final Path directory;

    public PersistentRsaKeys(Path directory) {
        this.directory = directory;
    }

    public RSAKey loadOrCreate() {
        try {
            Files.createDirectories(directory);
            ownerOnly(directory, true);
            try (FileChannel channel = FileChannel.open(directory.resolve(".key.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                Path privateFile = directory.resolve("private-key.pkcs8");
                Path publicFile = directory.resolve("public-key.der");
                if (!Files.exists(privateFile) && !Files.exists(publicFile)) {
                    var generator = KeyPairGenerator.getInstance("RSA");
                    generator.initialize(3072);
                    var pair = generator.generateKeyPair();
                    Files.write(privateFile, pair.getPrivate().getEncoded(), StandardOpenOption.CREATE_NEW);
                    ownerOnly(privateFile, false);
                    Files.write(publicFile, pair.getPublic().getEncoded(), StandardOpenOption.CREATE_NEW);
                }
                if (!Files.exists(privateFile) || !Files.exists(publicFile)) {
                    throw new IllegalStateException("Incomplete auth signing key pair; restore both key files");
                }
                ownerOnly(privateFile, false);
                var factory = KeyFactory.getInstance("RSA");
                var privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privateFile)));
                var publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicFile)));
                if (!privateKey.getModulus().equals(publicKey.getModulus())) {
                    throw new IllegalStateException("Auth signing key files do not belong to the same key pair");
                }
                var key = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
                return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(key.computeThumbprint().toString()).build();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load persistent auth signing keys from " + directory, exception);
        }
    }

    private static void ownerOnly(Path path, boolean directory) throws java.io.IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, directory
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }
}
