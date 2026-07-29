package com.game.backend.admin.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.admin")
public class AdminSecurityProperties {
    private String token;
    private List<String> allowedCidrs = new ArrayList<>();
    private List<String> defaultRoles = List.of("status", "access", "catalog", "ops", "security");
    private List<AdminCredential> identities = new ArrayList<>();

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public List<String> getAllowedCidrs() {
        return allowedCidrs;
    }

    public void setAllowedCidrs(List<String> allowedCidrs) {
        this.allowedCidrs = allowedCidrs == null ? new ArrayList<>() : allowedCidrs;
    }

    public List<String> getDefaultRoles() {
        return defaultRoles;
    }

    public void setDefaultRoles(List<String> defaultRoles) {
        this.defaultRoles = defaultRoles == null ? List.of() : defaultRoles;
    }

    public List<AdminCredential> getIdentities() {
        return identities;
    }

    public void setIdentities(List<AdminCredential> identities) {
        this.identities = identities == null ? new ArrayList<>() : identities;
    }

    public static class AdminCredential {
        private String id;
        private String token;
        private List<String> roles = new ArrayList<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles == null ? new ArrayList<>() : roles;
        }
    }
}
