package com.game.backend.common.config;

import com.game.backend.auth.application.JwtKeyRingProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionHardeningValidatorJwtKeyRingTest {
    @Test
    void shouldAcceptCompleteExternalKeyRingWithoutLegacyKeyPair() {
        JwtKeyRingProperties keyRing = keyRing("active", "file:/run/secrets/jwt-active-private", "file:/run/secrets/jwt-active-public");

        assertThatCode(() -> ProductionHardeningValidator.validateJwtKeyMaterial("", "", keyRing))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectIncompleteOrInlineKeyRingMaterial() {
        JwtKeyRingProperties incomplete = keyRing("active", "", "file:/run/secrets/jwt-active-public");
        assertThatThrownBy(() -> ProductionHardeningValidator.validateJwtKeyMaterial("", "", incomplete))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("jwt-keys[].private-key");

        JwtKeyRingProperties inline = keyRing(
            "active",
            "-----BEGIN PRIVATE KEY-----\nunsafe\n-----END PRIVATE KEY-----",
            "file:/run/secrets/jwt-active-public"
        );
        assertThatThrownBy(() -> ProductionHardeningValidator.validateJwtKeyMaterial("", "", inline))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("external secret material");
    }

    private JwtKeyRingProperties keyRing(String activeId, String privateKey, String publicKey) {
        JwtKeyRingProperties.JwtKey key = new JwtKeyRingProperties.JwtKey();
        key.setId(activeId);
        key.setPrivateKey(privateKey);
        key.setPublicKey(publicKey);
        JwtKeyRingProperties properties = new JwtKeyRingProperties();
        properties.setJwtActiveKeyId(activeId);
        properties.setJwtKeys(java.util.List.of(key));
        return properties;
    }
}
