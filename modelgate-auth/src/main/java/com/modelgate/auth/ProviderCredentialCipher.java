package com.modelgate.auth;

import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class ProviderCredentialCipher {
    private static final String VERSION = "aes-gcm-v1";
    private final String masterKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ProviderCredentialCipher(@Value("${modelgate.provider.master-key:}") String masterKey) {
        this.masterKey = masterKey;
    }

    public EncryptedCredential encrypt(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST, "Provider API key is required.");
        }
        try {
            byte[] iv = new byte[12];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
            return new EncryptedCredential(
                    VERSION,
                    Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(encrypted),
                    apiKey.substring(Math.max(0, apiKey.length() - 4)));
        } catch (ModelGateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ModelGateException(ErrorCode.INTERNAL_ERROR, "Provider credential encryption failed.");
        }
    }

    public String decrypt(String ciphertext, String version) {
        if (!VERSION.equals(version)) {
            throw new ModelGateException(ErrorCode.INTERNAL_ERROR, "Provider credential version is not supported.");
        }
        try {
            String[] parts = ciphertext.split("\\.", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid ciphertext format");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (ModelGateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ModelGateException(ErrorCode.INTERNAL_ERROR, "Provider credential cannot be decrypted.");
        }
    }

    private SecretKey secretKey() {
        try {
            byte[] key = Base64.getDecoder().decode(masterKey);
            if (key.length != 32) {
                throw new IllegalArgumentException("Expected 32 bytes");
            }
            return new SecretKeySpec(key, "AES");
        } catch (Exception ex) {
            throw new ModelGateException(ErrorCode.BAD_MODEL_REQUEST,
                    "Provider credential management requires a valid MODELGATE_PROVIDER_MASTER_KEY.");
        }
    }

    public record EncryptedCredential(String version, String ciphertext, String lastFour) {
    }
}
