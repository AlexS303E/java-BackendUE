package com.game.backend;

import com.game.backend.common.config.DeploymentProfileValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentProfileValidatorTest {
    @Test
    void shouldRejectMissingDeploymentProfile() {
        assertThatThrownBy(() -> DeploymentProfileValidator.validateForStartup(new String[0]))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("explicit deployment profile");
    }

    @Test
    void shouldRejectLocalAndProductionTogether() {
        assertThatThrownBy(() -> DeploymentProfileValidator.validateForStartup(new String[]{"local", "prod"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cannot activate local and production");
    }

    @Test
    void shouldAllowExplicitLocalOrProductionProfile() {
        assertThatCode(() -> DeploymentProfileValidator.validateForStartup(new String[]{"local"}))
            .doesNotThrowAnyException();
        assertThatCode(() -> DeploymentProfileValidator.validateForStartup(new String[]{"prod"}))
            .doesNotThrowAnyException();
    }
}
