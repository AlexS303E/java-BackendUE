package com.game.backend.admin.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionAdminIdentityValidatorTest {
    @Test
    void shouldRejectSharedTokenAndMissingProductionIdentity() {
        AdminSecurityProperties sharedToken = new AdminSecurityProperties();
        sharedToken.setToken("legacy-token");

        assertThatThrownBy(() -> ProductionAdminIdentityValidator.validateForStartup(new String[]{"prod"}, sharedToken))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("forbids app.admin.token");

        AdminSecurityProperties noIdentity = new AdminSecurityProperties();
        assertThatThrownBy(() -> ProductionAdminIdentityValidator.validateForStartup(new String[]{"prod"}, noIdentity))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("requires at least one");
    }

    @Test
    void shouldAllowIndependentProductionIdentity() {
        AdminSecurityProperties properties = new AdminSecurityProperties();
        AdminSecurityProperties.AdminCredential credential = new AdminSecurityProperties.AdminCredential();
        credential.setId("ops-alex");
        credential.setToken("independent-secret");
        credential.setRoles(List.of("ops"));
        properties.setIdentities(List.of(credential));

        assertThatCode(() -> ProductionAdminIdentityValidator.validateForStartup(new String[]{"prod"}, properties))
            .doesNotThrowAnyException();
    }
}
