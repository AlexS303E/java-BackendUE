package com.game.backend.serverauth.mtls;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Утилиты для вычисления и нормализации fingerprint X.509 сертификатов.
 */
public final class CertificateFingerprints {
    private CertificateFingerprints() {
    }

    /**
     * Возвращает SHA-256 fingerprint сертификата в формате lowercase hex без двоеточий.
     */
    public static String sha256Hex(X509Certificate certificate) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(certificate.getEncoded());
            return HexFormat.of().formatHex(hash).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        } catch (CertificateEncodingException exception) {
            throw new IllegalArgumentException("Cannot encode X.509 certificate", exception);
        }
    }

    /**
     * Нормализует fingerprint из БД/логов в формат lowercase hex без двоеточий.
     *
     * Важно:
     * - OpenSSL формат вроде "SHA256 Fingerprint=AA:BB:..." приводим к "aabb...".
     * - Dev/test значения вроде "dev-ds-fingerprint" не ломаем.
     */
    public static String normalizeSha256Fingerprint(String fingerprint) {
        if (fingerprint == null) {
            return null;
        }

        String normalized = fingerprint.trim();

        normalized = normalized.replaceFirst(
                "(?i)^sha[-_ ]?256\\s+fingerprint\\s*=\\s*",
                ""
        );
        normalized = normalized.replaceFirst(
                "(?i)^sha[-_ ]?256\\s*=\\s*",
                ""
        );
        normalized = normalized.replaceFirst(
                "(?i)^fingerprint\\s*=\\s*",
                ""
        );

        return normalized
                .replace(":", "")
                .replace(" ", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}