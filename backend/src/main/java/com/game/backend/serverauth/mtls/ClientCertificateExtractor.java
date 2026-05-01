package com.game.backend.serverauth.mtls;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.security.cert.X509Certificate;
import java.util.Optional;

/**
 * Извлекает client certificate, установленный servlet container после mTLS handshake.
 */
@Component
public class ClientCertificateExtractor {
    public static final String JAKARTA_X509_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
    public static final String JAVAX_X509_ATTRIBUTE = "javax.servlet.request.X509Certificate";

    public Optional<X509Certificate> firstClientCertificate(HttpServletRequest request) {
        Optional<X509Certificate> jakartaCertificate = firstCertificate(request.getAttribute(JAKARTA_X509_ATTRIBUTE));
        if (jakartaCertificate.isPresent()) {
            return jakartaCertificate;
        }
        return firstCertificate(request.getAttribute(JAVAX_X509_ATTRIBUTE));
    }

    private Optional<X509Certificate> firstCertificate(Object attribute) {
        if (attribute instanceof X509Certificate[] certificates && certificates.length > 0) {
            return Optional.ofNullable(certificates[0]);
        }
        return Optional.empty();
    }
}
