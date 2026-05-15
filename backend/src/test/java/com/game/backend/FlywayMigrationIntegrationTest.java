package com.game.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

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
class FlywayMigrationIntegrationTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldHaveAppliedAllMandatoryFlywayMigrationsSuccessfully() {
        assertThat(tableExists("flyway_schema_history")).isTrue();

        Integer failedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false",
                Integer.class
        );
        assertThat(failedCount).isZero();

        List<String> appliedVersions = jdbcTemplate.queryForList(
                """
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success = true
                      AND version IS NOT NULL
                    ORDER BY installed_rank
                    """,
                String.class
        );

        assertThat(appliedVersions).contains(
                "001", "002", "003", "004", "005", "006", "007", "008",
                "009", "010", "011", "012", "013", "014", "015", "016",
                "017", "018", "019", "020", "021", "022", "023", "024",
                "025", "026", "027", "028"
        );

        String v021Script = jdbcTemplate.queryForObject(
                """
                    SELECT script
                    FROM flyway_schema_history
                    WHERE version = '021'
                      AND success = true
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """,
                String.class
        );
        assertThat(v021Script).isEqualTo("V021__fk_match_id_pending_change_id.sql");

        String v023Script = jdbcTemplate.queryForObject(
                """
                    SELECT script
                    FROM flyway_schema_history
                    WHERE version = '023'
                      AND success = true
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """,
                String.class
        );
        assertThat(v023Script).isEqualTo("V023__outfit_item_team_rules.sql");

        String v024Script = jdbcTemplate.queryForObject(
                """
                    SELECT script
                    FROM flyway_schema_history
                    WHERE version = '024'
                      AND success = true
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """,
                String.class
        );
        assertThat(v024Script).isEqualTo("V024__match_id_fk_server_matches.sql");

        String v025Script = jdbcTemplate.queryForObject(
                """
                    SELECT script
                    FROM flyway_schema_history
                    WHERE version = '025'
                      AND success = true
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """,
                String.class
        );
        assertThat(v025Script).isEqualTo("V025__outbox_dead_letter_status.sql");

        String v026Script = jdbcTemplate.queryForObject(
                """
                    SELECT script
                    FROM flyway_schema_history
                    WHERE version = '026'
                      AND success = true
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """,
                String.class
        );
        assertThat(v026Script).isEqualTo("V026__runtime_op_updated_at.sql");

        String v027Script = jdbcTemplate.queryForObject(
                """
                    SELECT script
                    FROM flyway_schema_history
                    WHERE version = '027'
                      AND success = true
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """,
                String.class
        );
        assertThat(v027Script).isEqualTo("V027__postgres_observability.sql");

        String v028Script = jdbcTemplate.queryForObject(
                """
                    SELECT script
                    FROM flyway_schema_history
                    WHERE version = '028'
                      AND success = true
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """,
                String.class
        );
        assertThat(v028Script).isEqualTo("V028__audit_retention_indexes.sql");
    }

    @Test
    void shouldExposeCanonicalTablesIndexesForeignKeysAndCheckConstraints() {
        assertThat(allTablesExist(List.of(
                "realms",
                "class_definitions",
                "team_definitions",
                "weapon_slot_definitions",
                "clothing_slot_definitions",
                "production_factories",
                "player_accounts",
                "player_auth_sessions",
                "player_platform_links",
                "catalog_versions",
                "catalog_deployments",
                "catalog_id_migration_map",
                "catalog_items",
                "catalog_item_fragments",
                "item_team_rules",
                "item_class_rules",
                "weapon_module_mounts",
                "weapon_mount_allowed_modules",
                "player_access_projection_state",
                "player_item_access",
                "entitlement_ledger",
                "class_weapon_preset_rules",
                "class_weapon_slot_rules",
                "outfit_preset_rules",
                "player_weapon_preset_slot_unlocks",
                "player_outfit_preset_slot_unlocks",
                "player_weapon_presets",
                "player_weapon_preset_slots",
                "player_weapon_preset_weapon_configs",
                "player_weapon_preset_weapon_config_modules",
                "player_outfit_presets",
                "player_outfit_preset_items",
                "player_match_profiles",
                "runtime_preset_change_operations",
                "post_match_pending_changes",
                "api_idempotency_records",
                "outbox_events",
                "server_identities",
                "server_matches",
                "admin_audit_events",
                "server_audit_events",
                "server_runtime_events",
                "player_notifications",
                "runtime_operation_streams"
        ))).isTrue();

        assertThat(indexExists("idx_player_auth_sessions_player_status")).isTrue();
        assertThat(indexExists("uq_catalog_active_new_matches")).isTrue();
        assertThat(indexExists("uq_item_team_rules_all")).isTrue();
        assertThat(indexExists("uq_item_team_rules_specific")).isTrue();
        assertThat(indexExists("idx_post_match_pending_player_status")).isTrue();
        assertThat(indexExists("idx_post_match_pending_preset_status")).isTrue();
        assertThat(indexExists("idx_server_runtime_events_match_seq")).isTrue();
        assertThat(indexExists("idx_server_runtime_events_type_received")).isTrue();
        assertThat(indexExists("idx_player_notifications_player_status_created")).isTrue();
        assertThat(indexExists("idx_player_notifications_player_created")).isTrue();
        assertThat(indexExists("idx_catalog_items_catalog_enabled")).isTrue();
        assertThat(indexExists("idx_item_class_rules_lookup")).isTrue();
        assertThat(indexExists("idx_item_team_rules_lookup")).isTrue();
        assertThat(indexExists("idx_admin_audit_events_created_at")).isTrue();
        assertThat(indexExists("idx_server_audit_events_created_at")).isTrue();

        Map<String, Object> activeCatalogIndex = uniquePartialIndex("uq_catalog_active_new_matches");
        assertThat(activeCatalogIndex.get("is_unique")).isEqualTo(true);
        assertThat((String) activeCatalogIndex.get("predicate"))
                .contains("deployment_state")
                .contains("active")
                .contains("allow_new_matches");

        assertThat(foreignKeyCount("player_weapon_preset_weapon_config_modules")).isGreaterThanOrEqualTo(4);
        assertThat(foreignKeyCount("player_outfit_preset_items")).isGreaterThanOrEqualTo(2);
        assertThat(foreignKeyCount("player_match_profiles")).isGreaterThanOrEqualTo(5);
        assertThat(foreignKeyCount("server_runtime_events")).isGreaterThanOrEqualTo(3);

        assertThat(checkConstraintCount("player_accounts")).isGreaterThanOrEqualTo(1);
        assertThat(checkConstraintCount("catalog_versions")).isGreaterThanOrEqualTo(1);
        assertThat(checkConstraintCount("catalog_items")).isGreaterThanOrEqualTo(1);
        assertThat(checkConstraintCount("runtime_preset_change_operations")).isGreaterThanOrEqualTo(1);
        assertThat(checkConstraintCount("player_notifications")).isGreaterThanOrEqualTo(1);

        assertThat(columnType("catalog_item_fragments", "payload")).isEqualTo("jsonb");
        assertThat(columnType("player_match_profiles", "payload")).isEqualTo("jsonb");
        assertThat(columnType("outbox_events", "payload")).isEqualTo("jsonb");
        assertThat(columnType("server_identities", "allowed_scopes")).isEqualTo("_text");
    }

    @Test
    void shouldEnablePostgresObservabilityExtensionsAndSettings() {
        assertThat(rowExists("""
                SELECT 1
                FROM pg_extension
                WHERE extname = 'pg_stat_statements'
                """)).isTrue();

        assertThat(postgresSetting("shared_preload_libraries")).contains("pg_stat_statements");
        assertThat(postgresSetting("pg_stat_statements.track")).isEqualTo("all");
        assertThat(postgresSetting("track_io_timing")).isEqualTo("on");
        assertThat(postgresSetting("log_min_duration_statement")).isEqualTo("200");
    }

    @Test
    void shouldContainMvpSeedDataRequiredForIntegrationFlows() {
        restoreMvpActiveCatalogPointer();

        assertThat(rowExists("SELECT 1 FROM realms WHERE realm_id = 'global' AND is_active = true")).isTrue();
        assertThat(rowExists("SELECT 1 FROM class_definitions WHERE class_tag = 'class.assault' AND is_active = true")).isTrue();
        assertThat(rowExists("SELECT 1 FROM team_definitions WHERE team_tag = 'team.red' AND is_active = true")).isTrue();
        assertThat(rowExists("SELECT 1 FROM team_definitions WHERE team_tag = 'team.blue' AND is_active = true")).isTrue();

        assertThat(rowExists("SELECT 1 FROM catalog_versions WHERE catalog_version = 1 AND state = 'active'")).isTrue();
        assertThat(rowExists("""
                SELECT 1
                FROM catalog_deployments
                WHERE realm_id = 'global'
                  AND catalog_version = 1
                  AND deployment_state = 'active'
                  AND allow_new_matches = true
                """)).isTrue();

        assertThat(rowExists("SELECT 1 FROM catalog_items WHERE item_id = 'weapon.ak12' AND catalog_version = 1 AND item_type = 'weapon' AND is_enabled = true")).isTrue();
        assertThat(rowExists("SELECT 1 FROM catalog_items WHERE item_id = 'module.scope.red_dot_01' AND catalog_version = 1 AND item_type = 'module' AND is_enabled = true")).isTrue();
        assertThat(rowExists("SELECT 1 FROM catalog_items WHERE item_id = 'clothing.team_red.jacket_01' AND catalog_version = 1 AND item_type = 'clothing' AND is_enabled = true")).isTrue();
        assertThat(rowExists("SELECT 1 FROM catalog_items WHERE item_id = 'clothing.team_blue.jacket_01' AND catalog_version = 1 AND item_type = 'clothing' AND is_enabled = true")).isTrue();

        assertThat(rowExists("""
                SELECT 1
                FROM weapon_module_mounts
                WHERE mount_id = 'weapon.ak12.mount.scope.01'
                  AND catalog_version = 1
                  AND weapon_id = 'weapon.ak12'
                """)).isTrue();
        assertThat(rowExists("""
                SELECT 1
                FROM weapon_mount_allowed_modules
                WHERE mount_id = 'weapon.ak12.mount.scope.01'
                  AND module_id = 'module.scope.red_dot_01'
                  AND catalog_version = 1
                """)).isTrue();

        assertThat(rowExists("SELECT 1 FROM class_weapon_slot_rules WHERE class_tag = 'class.assault' AND weapon_slot_id = 'primary' AND is_allowed = true")).isTrue();
        assertThat(rowExists("SELECT 1 FROM class_weapon_slot_rules WHERE class_tag = 'class.assault' AND weapon_slot_id = 'grenade' AND is_allowed = true")).isTrue();
        assertThat(rowExists("SELECT 1 FROM outfit_preset_rules WHERE team_tag = 'team.red' AND class_tag = 'class.assault' AND base_outfit_preset_count >= 1")).isTrue();
        assertThat(rowExists("SELECT 1 FROM outfit_preset_rules WHERE team_tag = 'team.blue' AND class_tag = 'class.assault' AND base_outfit_preset_count >= 1")).isTrue();

        assertThat(rowExists("""
                SELECT 1
                FROM server_identities
                WHERE server_id = '10000000-0000-0000-0000-000000000001'
                  AND status = 'active'
                  AND 'match_profile:read' = ANY(allowed_scopes)
                  AND 'runtime_preset_change:write' = ANY(allowed_scopes)
                  AND 'runtime_event:write' = ANY(allowed_scopes)
                """)).isTrue();
        assertThat(rowExists("""
                SELECT 1
                FROM server_identities
                WHERE server_id = '10000000-0000-0000-0000-000000000002'
                  AND status = 'active'
                  AND 'match_profile:read' = ANY(allowed_scopes)
                """)).isTrue();
    }

    private void restoreMvpActiveCatalogPointer() {
        jdbcTemplate.update(
                """
                    UPDATE catalog_deployments
                    SET deployment_state = 'previous',
                        rollout_percent = 0,
                        allow_new_matches = false,
                        allow_existing_matches = true
                    WHERE realm_id = 'global'
                      AND catalog_version <> 1
                      AND deployment_state = 'active'
                      AND allow_new_matches = true
                    """
        );
        jdbcTemplate.update(
                """
                    UPDATE catalog_deployments
                    SET deployment_state = 'active',
                        rollout_percent = 100,
                        allow_new_matches = true,
                        allow_existing_matches = true,
                        retired_at = null
                    WHERE realm_id = 'global'
                      AND catalog_version = 1
                    """
        );
        jdbcTemplate.update(
                """
                    UPDATE catalog_versions
                    SET state = 'validated'
                    WHERE catalog_version <> 1
                      AND state = 'active'
                    """
        );
        jdbcTemplate.update(
                """
                    UPDATE catalog_versions
                    SET state = 'active',
                        retired_at = null
                    WHERE catalog_version = 1
                    """
        );
    }

    private boolean allTablesExist(List<String> tableNames) {
        for (String tableName : tableNames) {
            if (!tableExists(tableName)) {
                return false;
            }
        }
        return true;
    }

    private boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL",
                Boolean.class,
                "public." + tableName
        ));
    }

    private boolean indexExists(String indexName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                    SELECT EXISTS(
                      SELECT 1
                      FROM pg_indexes
                      WHERE schemaname = 'public'
                        AND indexname = ?
                    )
                    """,
                Boolean.class,
                indexName
        ));
    }

    private Map<String, Object> uniquePartialIndex(String indexName) {
        return jdbcTemplate.queryForMap(
                """
                    SELECT
                      idx.indisunique AS is_unique,
                      pg_get_expr(idx.indpred, idx.indrelid) AS predicate
                    FROM pg_index idx
                    JOIN pg_class cls ON cls.oid = idx.indexrelid
                    JOIN pg_namespace ns ON ns.oid = cls.relnamespace
                    WHERE ns.nspname = 'public'
                      AND cls.relname = ?
                    """,
                indexName
        );
    }

    private int foreignKeyCount(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM pg_constraint
                    WHERE conrelid = ?::regclass
                      AND contype = 'f'
                    """,
                Integer.class,
                "public." + tableName
        );
        return count == null ? 0 : count;
    }

    private int checkConstraintCount(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM pg_constraint
                    WHERE conrelid = ?::regclass
                      AND contype = 'c'
                    """,
                Integer.class,
                "public." + tableName
        );
        return count == null ? 0 : count;
    }

    private String columnType(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                """
                    SELECT udt_name
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = ?
                      AND column_name = ?
                    """,
                String.class,
                tableName,
                columnName
        );
    }

    private String postgresSetting(String settingName) {
        return jdbcTemplate.queryForObject(
                """
                    SELECT setting
                    FROM pg_settings
                    WHERE name = ?
                    """,
                String.class,
                settingName
        );
    }

    private boolean rowExists(String sql) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS(" + sql + ")",
                Boolean.class
        ));
    }
}
