package com.game.backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

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
@Rollback
class QueryIndexCoverageIntegrationTest {
    private static final String ITEM_ID = "weapon.ak12";
    private static final long CATALOG_VERSION = 1L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID playerId;

    @BeforeEach
    void setUpPlayerData() {
        playerId = UUID.randomUUID();
        jdbcTemplate.update(
            """
                INSERT INTO player_accounts(player_id, login_name, password_hash, status, created_at, updated_at)
                VALUES (?, ?, 'test-hash', 'active', now(), now())
                """,
            playerId,
            "index-coverage-" + playerId
        );
        jdbcTemplate.update(
            """
                INSERT INTO player_item_access(
                  player_id, item_id, catalog_version, is_hidden, is_locked_in_shop,
                  is_locked_by_quest, is_disabled, updated_at
                )
                VALUES (?, ?, ?, false, false, false, false, now())
                """,
            playerId,
            ITEM_ID,
            CATALOG_VERSION
        );
        jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
    }

    @Test
    void validateCanUseBatchShouldUseCoveringIndexes() {
        String plan = explain(
            """
                SELECT ci.item_id
                FROM catalog_items ci
                JOIN player_item_access pia
                  ON pia.item_id = ci.item_id
                 AND pia.catalog_version = ci.catalog_version
                 AND pia.player_id = ?
                WHERE ci.catalog_version = ?
                  AND ci.is_enabled = true
                  AND pia.is_hidden = false
                  AND pia.is_locked_in_shop = false
                  AND pia.is_locked_by_quest = false
                  AND pia.is_disabled = false
                  AND ci.item_id IN (?)
                  AND NOT EXISTS (
                    SELECT 1 FROM item_class_rules icr
                    WHERE icr.item_id = ci.item_id
                      AND icr.catalog_version = ci.catalog_version
                      AND icr.class_tag = ?
                      AND icr.rule_effect = 'deny'
                  )
                  AND (
                    NOT EXISTS (
                      SELECT 1 FROM item_class_rules icr
                      WHERE icr.item_id = ci.item_id
                        AND icr.catalog_version = ci.catalog_version
                        AND icr.rule_effect = 'allow'
                    )
                    OR EXISTS (
                      SELECT 1 FROM item_class_rules icr
                      WHERE icr.item_id = ci.item_id
                        AND icr.catalog_version = ci.catalog_version
                        AND icr.class_tag = ?
                        AND icr.rule_effect = 'allow'
                    )
                  )
                """,
            playerId,
            CATALOG_VERSION,
            ITEM_ID,
            "class.assault",
            "class.assault"
        );

        assertThat(plan)
            .contains("idx_catalog_items_catalog_enabled")
            .contains("idx_item_class_rules_lookup")
            .contains("player_item_access_pkey");
    }

    @Test
    void freshMatchProfileLookupShouldUseCoveringPartialIndex() {
        jdbcTemplate.update(
            """
                INSERT INTO player_match_profiles(
                  profile_id, player_id, realm_id, class_tag, team_tag,
                  weapon_preset_slot, outfit_preset_slot, weapon_preset_revision,
                  outfit_preset_revision, access_revision, catalog_version,
                  profile_revision, payload, payload_schema_version, is_stale,
                  generated_at, expires_at
                )
                VALUES (?, ?, 'global', 'class.assault', 'team.red',
                        0, 0, 1, 1, 1, ?, 1, '{}'::jsonb, 1, false,
                        now(), now() + interval '5 minutes')
                """,
            UUID.randomUUID(),
            playerId,
            CATALOG_VERSION
        );

        String plan = explain(
            """
                SELECT payload
                FROM player_match_profiles
                WHERE player_id = ?
                  AND realm_id = 'global'
                  AND class_tag = 'class.assault'
                  AND team_tag = 'team.red'
                  AND weapon_preset_slot = 0
                  AND outfit_preset_slot = 0
                  AND catalog_version = ?
                  AND weapon_preset_revision = 1
                  AND outfit_preset_revision = 1
                  AND access_revision = 1
                  AND is_stale = false
                  AND expires_at > NOW()
                LIMIT 1
                """,
            playerId,
            CATALOG_VERSION
        );

        assertThat(plan)
            .contains("Index Only Scan")
            .contains("idx_match_profiles_fresh_dependency_lookup");
    }

    private String explain(String query, Object... args) {
        List<String> planRows = jdbcTemplate.queryForList(
            "EXPLAIN (ANALYZE, COSTS OFF, FORMAT TEXT) " + query,
            String.class,
            args
        );
        return String.join(System.lineSeparator(), planRows);
    }
}
