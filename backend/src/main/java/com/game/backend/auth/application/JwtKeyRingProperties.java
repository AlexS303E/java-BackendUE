package com.game.backend.auth.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.auth")
public class JwtKeyRingProperties {
    private String jwtActiveKeyId;
    private List<JwtKey> jwtKeys = new ArrayList<>();

    public String getJwtActiveKeyId() {
        return jwtActiveKeyId;
    }

    public void setJwtActiveKeyId(String jwtActiveKeyId) {
        this.jwtActiveKeyId = jwtActiveKeyId;
    }

    public List<JwtKey> getJwtKeys() {
        return jwtKeys;
    }

    public void setJwtKeys(List<JwtKey> jwtKeys) {
        this.jwtKeys = jwtKeys == null ? new ArrayList<>() : jwtKeys;
    }

    public static class JwtKey {
        private String id;
        private String privateKey;
        private String publicKey;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }
    }
}
