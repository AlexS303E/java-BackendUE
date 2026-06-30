package com.game.backend;

import com.game.backend.auth.application.AuthService;
import com.game.backend.runtimechanges.api.RuntimePresetChangePayload;
import com.game.backend.runtimechanges.api.RuntimePresetChangeRequest;
import com.game.backend.runtimechanges.api.RuntimePresetChangeStep;
import com.game.backend.runtimechanges.application.RuntimeOperationRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
class RuntimeOperationRecorderIntegrationTest {
    private static final String DEV_SERVER_ID = "10000000-0000-0000-0000-000000000001";

    @Autowired
    private RuntimeOperationRecorder recorder;

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldInsertFindAndMarkRuntimeOperationTransitions() {
        RuntimePresetChangeRequest request = request();
        OffsetDateTime now = OffsetDateTime.now();

        assertThat(recorder.insertProcessing(request, "hash-1", now)).isEqualTo(1);
        assertThat(recorder.insertProcessing(request, "hash-1", now)).isZero();

        RuntimeOperationRecorder.ExistingOperation processing = recorder.find(request.operationId());
        assertThat(processing).isNotNull();
        assertThat(processing.status()).isEqualTo("processing");
        assertThat(processing.requestHash()).isEqualTo("hash-1");

        recorder.markApplied(request.operationId(), 2, now.plusSeconds(1));

        RuntimeOperationRecorder.ExistingOperation applied = recorder.find(request.operationId());
        assertThat(applied.status()).isEqualTo("applied");
        assertThat(applied.resultRevision()).isEqualTo(2);
        assertThat(operationStatus(request.operationId())).isEqualTo("applied");
    }

    private String operationStatus(UUID operationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM runtime_preset_change_operations WHERE operation_id = ?",
                String.class,
                operationId
        );
    }

    private RuntimePresetChangeRequest request() {
        UUID playerId = registerPlayer();
        UUID matchId = createMatch();
        return new RuntimePresetChangeRequest(
                UUID.randomUUID(),
                1L,
                matchId,
                playerId,
                "class.assault",
                1,
                1L,
                new RuntimePresetChangePayload(
                        1,
                        List.of(new RuntimePresetChangeStep("clear_weapon", "primary", null, null, null))
                )
        );
    }

    private UUID registerPlayer() {
        String loginName = "runtime_recorder_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return authService.register(loginName, "password123").playerId();
    }

    private UUID createMatch() {
        UUID matchId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                    INSERT INTO server_matches(
                      match_id,
                      server_id,
                      realm_id,
                      status,
                      created_at
                    )
                    VALUES (?, ?, 'global', 'running', ?)
                    """,
                matchId,
                UUID.fromString(DEV_SERVER_ID),
                OffsetDateTime.now()
        );
        return matchId;
    }
}
