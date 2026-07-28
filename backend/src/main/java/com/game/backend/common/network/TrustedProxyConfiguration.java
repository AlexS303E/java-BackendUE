package com.game.backend.common.network;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TrustedProxyProperties.class)
public class TrustedProxyConfiguration {
}
