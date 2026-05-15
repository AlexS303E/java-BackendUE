package com.game.backend.common.audit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuditRetentionProperties.class)
public class AuditRetentionConfiguration {
}
