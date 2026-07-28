package com.game.backend.common.network;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.network")
public class TrustedProxyProperties {
    private List<String> trustedProxyCidrs = new ArrayList<>();

    public List<String> getTrustedProxyCidrs() {
        return trustedProxyCidrs;
    }

    public void setTrustedProxyCidrs(List<String> trustedProxyCidrs) {
        this.trustedProxyCidrs = trustedProxyCidrs == null ? new ArrayList<>() : trustedProxyCidrs;
    }
}
