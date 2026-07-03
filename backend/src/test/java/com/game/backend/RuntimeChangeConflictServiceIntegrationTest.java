package com.game.backend;

import com.game.backend.auth.application.AuthService;
import com.game.backend.runtimechanges.api.RuntimePresetChangePayload;
import com.game.backend.runtimechanges.api.RuntimePresetChangeStep;
import com.game.backend.runtimechanges.application.RuntimeChangeConflictService;
import com.game.backend.runtimechanges.application.RuntimePresetChangeCommand;
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
class RuntimeChangeConflictServiceIntegrationTest {
    private static final String DEV_SERVER_ID = "10000000-0000-0000-0000-000000000001";
    private static final String CLASS_TAG = "class.assault";
    private static final int WEAPON_PRESET_SLOT = 1;

    @Autowired
    private RuntimeChangeConflictService conflictService;

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldCreatePendingChangeOutboxEventAndPlayerNotification() {
        UUID playerId = registerPlayer();
        UUID matchId = createMatch();
        RuntimePresetChangeCommand request = request(playerId, matchId);

        UUID pendingChangeId = conflictService.createRevisionConflict(request, 5, OffsetDateTime.now());

        assertThat(pendingChangeStatus(pendingChangeId)).isEqualTo("pending");
        assertThat(pendingChangeReason(pendingChangeId)).isEqualTo("revision_conflict");
        assertThat(outboxEventCount(pendingChangeId)).isEqualTo(1);
        assertThat(notificationCount(playerId, pendingChangeId)).isEqualTo(1);
    }

    private UUID registerPlayer() {
        String loginName = "runtime_conflict_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private RuntimePresetChangeCommand request(UUID playerId, UUID matchId) {
        return new RuntimePresetChangeCommand(
                UUID.randomUUID(),
                1L,
                matchId,
                playerId,
                CLASS_TAG,
                WEAPON_PRESET_SLOT,
                1L,
                new RuntimePresetChangePayload(
                        1,
                        List.of(new RuntimePresetChangeStep("clear_weapon", "primary", null, null, null))
                )
        );
    }

    private String pendingChangeStatus(UUID pendingChangeId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM post_match_pending_changes WHERE change_id = ?",
                String.class,
                pendingChangeId
        );
    }

    private String pendingChangeReason(UUID pendingChangeId) {
        return jdbcTemplate.queryForObject(
                "SELECT reason_code FROM post_match_pending_changes WHERE change_id = ?",
                String.class,
                pendingChangeId
        );
    }

    private int outboxEventCount(UUID pendingChangeId) {
        return jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM outbox_events
                    WHERE event_type = 'post_match_pending_change.created'
                      AND aggregate_type = 'post_match_pending_change'
                      AND aggregate_id = ?
                    """,
                Integer.class,
                pendingChangeId.toString()
        );
    }

    private int notificationCount(UUID playerId, UUID pendingChangeId) {
        return jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM player_notifications
                    WHERE player_id = ?
                      AND event_type = 'post_match_pending_change.created'
                      AND aggregate_type = 'post_match_pending_change'
                      AND aggregate_id = ?
                      AND status = 'unread'
                    """,
                Integer.class,
                playerId,
                pendingChangeId.toString()
        );
    }
}
