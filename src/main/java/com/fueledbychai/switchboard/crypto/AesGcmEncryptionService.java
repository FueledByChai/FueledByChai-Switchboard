package com.fueledbychai.switchboard.crypto;

import com.fueledbychai.switchboard.config.SwitchboardSecretsProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

@Service
public class AesGcmEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(AesGcmEncryptionService.class);
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;

    private final SwitchboardSecretsProperties secretsProperties;
    private final SecureRandom secureRandom = new SecureRandom();
    private SecretKey secretKey;

    public AesGcmEncryptionService(SwitchboardSecretsProperties secretsProperties) {
        this.secretsProperties = secretsProperties;
    }

    @PostConstruct
    public void init() {
        this.secretKey = loadOrCreateKey();
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return "";
        }

        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt profile data.", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return "";
        }

        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length <= IV_LENGTH) {
                throw new IllegalArgumentException("Encrypted payload is invalid.");
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Unable to decrypt profile data.", e);
        }
    }

    private SecretKey loadOrCreateKey() {
        String configuredKey = secretsProperties.getMasterKey();
        if (configuredKey != null && !configuredKey.isBlank()) {
            return decodeKey(configuredKey);
        }

        Path keyPath = Path.of(secretsProperties.getMasterKeyPath()).toAbsolutePath().normalize();
        try {
            if (Files.exists(keyPath)) {
                return decodeKey(Files.readString(keyPath, StandardCharsets.UTF_8).trim());
            }

            if (keyPath.getParent() != null) {
                Files.createDirectories(keyPath.getParent());
            }

            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(256);
            SecretKey generated = keyGenerator.generateKey();
            String encoded = Base64.getEncoder().encodeToString(generated.getEncoded());
            Files.writeString(keyPath, encoded, StandardCharsets.UTF_8);
            tightenPermissions(keyPath);
            log.warn("Generated a new Switchboard master key at {}. Keep this file safe; it is required to decrypt stored exchange profiles.",
                    keyPath);
            return generated;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Unable to initialize the Switchboard master key.", e);
        }
    }

    private SecretKey decodeKey(String encoded) {
        byte[] decoded = Base64.getDecoder().decode(encoded);
        return new SecretKeySpec(decoded, "AES");
    }

    private void tightenPermissions(Path path) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            log.debug("Unable to set POSIX permissions on {}", path);
        }
    }
}
