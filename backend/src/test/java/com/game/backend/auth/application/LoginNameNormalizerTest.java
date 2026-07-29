package com.game.backend.auth.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginNameNormalizerTest {
    @Test
    void shouldTrimFoldCaseAndNormalizeCompatibilityCharacters() {
        assertThat(LoginNameNormalizer.normalize("  Player_01  ")).isEqualTo("player_01");
        assertThat(LoginNameNormalizer.normalize("ＰＬＡＹＥＲ_01")).isEqualTo("player_01");
    }

    @Test
    void shouldProduceSafeValueForMissingLoginName() {
        assertThat(LoginNameNormalizer.normalize(null)).isEmpty();
    }
}
