package com.example.tallerintegrador.service.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class IdHasher {

    private final SecretKeySpec secretKey;

    public IdHasher(@Value("${app.id-hash-secret:MySuperSecretKeyForHashingIds12!}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        byte[] key16 = new byte[16];
        System.arraycopy(keyBytes, 0, key16, 0, Math.min(keyBytes.length, 16));
        this.secretKey = new SecretKeySpec(key16, "AES");
    }

    public String encode(Long id) {
        if (id == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            
            // Convertir Long a un arreglo de 8 bytes
            byte[] idBytes = ByteBuffer.allocate(8).putLong(id).array();
            
            byte[] encrypted = cipher.doFinal(idBytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Error encoding ID", e);
        }
    }

    public Long decode(String hash) {
        if (hash == null || hash.trim().isEmpty()) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            
            byte[] decodedBytes = Base64.getUrlDecoder().decode(hash);
            byte[] decrypted = cipher.doFinal(decodedBytes);
            
            // Convertir de nuevo los 8 bytes a Long
            return ByteBuffer.wrap(decrypted).getLong();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid ID hash: " + hash, e);
        }
    }
}
