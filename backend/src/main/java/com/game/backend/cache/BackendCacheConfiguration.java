package com.game.backend.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BackendCacheProperties.class)
public class BackendCacheConfiguration {
}
