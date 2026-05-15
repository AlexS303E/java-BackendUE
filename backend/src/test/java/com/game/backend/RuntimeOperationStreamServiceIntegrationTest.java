package com.game.backend;

import com.game.backend.common.api.ApiException;
import com.game.backend.runtimechanges.api.RuntimePresetChangePayload;
import com.game.backend.runtimechanges.api.RuntimePresetChangeRequest;
import com.game.backend.runtimechanges.api.RuntimePresetChangeStep;
import com.game.backend.runtimechanges.application.RuntimeOperationStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.outbox.worker-enabled=false",
                "app.server-auth.mtls.enabled=false",
                "app.server-auth.mtls.require-private-port=false",
                "app.server-auth.mtls.allow-header-fingerprint-fallback=true"
        }
)
@ActiveProfiles("local")
@Transactional
class RuntimeOperationStreamServiceIntegrationTest {
    @Autowired
    private RuntimeOperationStreamService streamService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldValidateNextSequenceAndAdvanceStream() {
        RuntimePresetChangeRequest first = request(1);
        streamService.lockAndValidateNextSequence(first);
        streamService.advance(first);

        assertThat(lastAppliedSeq(first.matchId(), first.playerId())).isEqualTo(1);

        RuntimePresetChangeRequest second = new RuntimePresetChangeRequest(
                UUID.randomUUID(),
                2L,
                first.matchId(),
                first.playerId(),
                first.classTag(),
                first.weaponPresetSlot(),
                first.baseWeaponPresetRevision(),
                first.runtimeChangePayload()
        );
        streamService.lockAndValidateNextSequence(second);
    }

    @Test
    void shouldRejectOutOfOrderSequence() {
        RuntimePresetChangeRequest request = request(2);

        assertThatThrownBy(() -> streamService.lockAndValidateNextSequence(request))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("RUNTIME_OPERATION_SEQ_OUT_OF_ORDER"));
    }

    private long lastAppliedSeq(UUID matchId, UUID playerId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_applied_seq FROM runtime_operation_streams WHERE match_id = ? AND player_id = ?",
                Long.class,
                matchId,
                playerId
        );
    }

    private RuntimePresetChangeRequest request(long operationSeq) {
        return new RuntimePresetChangeRequest(
                UUID.randomUUID(),
                operationSeq,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "class.assault",
                1,
                1L,
                new RuntimePresetChangePayload(
                        1,
                        List.of(new RuntimePresetChangeStep("clear_weapon", "primary", null, null, null))
                )
        );
    }
}
