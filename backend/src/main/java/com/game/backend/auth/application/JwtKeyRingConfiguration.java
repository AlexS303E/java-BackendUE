package com.game.backend.auth.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JwtKeyRingProperties.class)
public class JwtKeyRingConfiguration {
}
