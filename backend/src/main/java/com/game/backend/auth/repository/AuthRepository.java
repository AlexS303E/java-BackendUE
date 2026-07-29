package com.game.backend.auth.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class AuthRepository extends JdbcRepository {
    public record Account(
        UUID playerId,
        String loginName,
        String passwordHash,
        String status
    ) {
    }

    public record RefreshSession(
        UUID sessionId,
        UUID playerId,
        String loginName,
        String accountStatus,
        OffsetDateTime expiresAt
    ) {
    }

    public record WeaponBootstrapDefault(
        String classTag,
        int presetSlot,
        String weaponSlotId,
        String weaponId,
        String mountId,
        String moduleId
    ) {
    }

    public record OutfitBootstrapDefault(
        String teamTag,
        String classTag,
        int presetSlot,
        String clothingSlotId,
        String itemId
    ) {
    }

    public AuthRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    public void insertPlayerAccount(UUID playerId, String loginName, String passwordHash, OffsetDateTime now) {
        update(
            """
                INSERT INTO player_accounts(
                  player_id,
                  login_name,
                  password_hash,
                  status,
                  created_at,
                  updated_at
                )
                VALUES (?, ?, ?, 'active', ?, ?)
                """,
            playerId,
            loginName,
            passwordHash,
            now,
            now
        );
    }

    public List<Account> findAccountsByLoginName(String loginName) {
        return query(
            """
                SELECT player_id, login_name, password_hash, status
                FROM player_accounts
                WHERE lower(btrim(login_name)) = ?
                """,
            (rs, rowNum) -> new Account(
                rs.getObject("player_id", UUID.class),
                rs.getString("login_name"),
                rs.getString("password_hash"),
                rs.getString("status")
            ),
            loginName
        );
    }

    public List<RefreshSession> lockActiveRefreshSessions(String refreshTokenHash) {
        return query(
            """
                SELECT
                  pas.session_id,
                  pas.player_id,
                  pa.login_name,
                  pa.status AS account_status,
                  pas.expires_at
                FROM player_auth_sessions pas
                JOIN player_accounts pa ON pa.player_id = pas.player_id
                WHERE pas.refresh_token_hash = ?
                  AND pas.status = 'active'
                FOR UPDATE OF pas
                """,
            (rs, rowNum) -> new RefreshSession(
                rs.getObject("session_id", UUID.class),
                rs.getObject("player_id", UUID.class),
                rs.getString("login_name"),
                rs.getString("account_status"),
                rs.getObject("expires_at", OffsetDateTime.class)
            ),
            refreshTokenHash
        );
    }

    public void expireAuthSession(UUID sessionId) {
        update(
            """
                UPDATE player_auth_sessions
                SET status = 'expired'
                WHERE session_id = ?
                """,
            sessionId
        );
    }

    public void revokeAuthSession(UUID sessionId, OffsetDateTime now) {
        update(
            """
                UPDATE player_auth_sessions
                SET status = 'revoked',
                    revoked_at = ?
                WHERE session_id = ?
                """,
            now,
            sessionId
        );
    }

    public void revokeActiveSessionByRefreshTokenHash(String refreshTokenHash, OffsetDateTime now) {
        update(
            """
                UPDATE player_auth_sessions
                SET status = 'revoked',
                    revoked_at = ?
                WHERE refresh_token_hash = ?
                  AND status = 'active'
                """,
            now,
            refreshTokenHash
        );
    }

    public void insertAuthSession(
        UUID sessionId,
        UUID playerId,
        String refreshTokenHash,
        OffsetDateTime now,
        OffsetDateTime expiresAt
    ) {
        update(
            """
                INSERT INTO player_auth_sessions(
                  session_id,
                  player_id,
                  refresh_token_hash,
                  status,
                  created_at,
                  expires_at
                )
                VALUES (?, ?, ?, 'active', ?, ?)
                """,
            sessionId,
            playerId,
            refreshTokenHash,
            now,
            expiresAt
        );
    }

    public List<Long> findActiveCatalogVersionsForBootstrap(String realmId) {
        return queryForList(
            """
                SELECT catalog_version
                FROM catalog_deployments
                WHERE realm_id = ?
                  AND deployment_state = 'active'
                  AND allow_new_matches = true
                ORDER BY activated_at DESC NULLS LAST, catalog_version DESC
                LIMIT 1
                """,
            Long.class,
            realmId
        );
    }

    public List<WeaponBootstrapDefault> findWeaponBootstrapDefaults(long catalogVersion) {
        return query(
            """
                SELECT class_tag, preset_slot, weapon_slot_id, weapon_id, mount_id, module_id
                FROM catalog_bootstrap_weapon_defaults
                WHERE catalog_version = ?
                ORDER BY class_tag, preset_slot
                """,
            (rs, rowNum) -> new WeaponBootstrapDefault(
                rs.getString("class_tag"),
                rs.getInt("preset_slot"),
                rs.getString("weapon_slot_id"),
                rs.getString("weapon_id"),
                rs.getString("mount_id"),
                rs.getString("module_id")
            ),
            catalogVersion
        );
    }

    public List<OutfitBootstrapDefault> findOutfitBootstrapDefaults(long catalogVersion) {
        return query(
            """
                SELECT team_tag, class_tag, outfit_preset_slot, clothing_slot_id, item_id
                FROM catalog_bootstrap_outfit_defaults
                WHERE catalog_version = ?
                ORDER BY team_tag, class_tag, outfit_preset_slot, clothing_slot_id
                """,
            (rs, rowNum) -> new OutfitBootstrapDefault(
                rs.getString("team_tag"),
                rs.getString("class_tag"),
                rs.getInt("outfit_preset_slot"),
                rs.getString("clothing_slot_id"),
                rs.getString("item_id")
            ),
            catalogVersion
        );
    }

    public void insertAccessProjectionState(UUID playerId, OffsetDateTime now) {
        update(
            """
                INSERT INTO player_access_projection_state(
                  player_id,
                  access_revision,
                  projection_rebuilt_at
                )
                VALUES (?, 1, ?)
                """,
            playerId,
            now
        );
    }

    public void insertBootstrapEntitlementLedgerEvents(
        UUID playerId,
        long catalogVersion,
        OffsetDateTime now
    ) {
        update(
            """
                INSERT INTO entitlement_ledger(
                  ledger_event_id,
                  player_id,
                  item_id,
                  catalog_version,
                  event_type,
                  source_type,
                  actor_type,
                  actor_id,
                  idempotency_key,
                  created_at
                )
                SELECT
                  gen_random_uuid(),
                  ?,
                  item_id,
                  catalog_version,
                  'reveal_item',
                  'default',
                  'system',
                  'bootstrap',
                  'bootstrap:' || catalog_version || ':' || item_id,
                  ?
                FROM catalog_items
                WHERE catalog_version = ?
                  AND is_enabled = true
                """,
            playerId,
            now,
            catalogVersion
        );
    }

    public void insertEnabledCatalogAccess(UUID playerId, long catalogVersion, OffsetDateTime now) {
        update(
            """
                INSERT INTO player_item_access(
                  player_id,
                  item_id,
                  catalog_version,
                  is_hidden,
                  is_locked_in_shop,
                  is_locked_by_quest,
                  is_disabled,
                  updated_at
                )
                SELECT
                  ?,
                  item_id,
                  catalog_version,
                  false,
                  false,
                  false,
                  false,
                  ?
                FROM catalog_items
                WHERE catalog_version = ?
                  AND is_enabled = true
                """,
            playerId,
            now,
            catalogVersion
        );
    }

    public void insertDefaultWeaponPreset(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        OffsetDateTime now
    ) {
        update(
            """
                INSERT INTO player_weapon_presets(
                  player_id,
                  class_tag,
                  preset_slot,
                  catalog_version,
                  revision,
                  sanitized,
                  updated_at
                )
                VALUES (?, ?, ?, ?, 1, false, ?)
                """,
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            now
        );
    }

    public void insertDefaultWeaponPresetSlots(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String defaultWeaponSlotId,
        String defaultWeaponId
    ) {
        update(
            """
                INSERT INTO player_weapon_preset_slots(
                  player_id,
                  class_tag,
                  preset_slot,
                  catalog_version,
                  weapon_slot_id,
                  selected_weapon_id
                )
                SELECT
                  ?,
                  ?,
                  ?,
                  ?,
                  weapon_slot_id,
                  CASE WHEN weapon_slot_id = ? THEN ? ELSE NULL END
                FROM class_weapon_slot_rules
                WHERE class_tag = ?
                  AND is_allowed = true
                """,
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            defaultWeaponSlotId,
            defaultWeaponId,
            classTag
        );
    }

    public void insertDefaultWeaponConfig(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId,
        OffsetDateTime now
    ) {
        update(
            """
                INSERT INTO player_weapon_preset_weapon_configs(
                  player_id,
                  class_tag,
                  preset_slot,
                  catalog_version,
                  weapon_slot_id,
                  weapon_id,
                  config_revision,
                  last_used_at
                )
                VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                """,
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            weaponSlotId,
            weaponId,
            now
        );
    }

    public void insertDefaultWeaponConfigModule(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId,
        String mountId,
        String moduleId
    ) {
        update(
            """
                INSERT INTO player_weapon_preset_weapon_config_modules(
                  player_id,
                  class_tag,
                  preset_slot,
                  catalog_version,
                  weapon_slot_id,
                  weapon_id,
                  mount_id,
                  module_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            weaponSlotId,
            weaponId,
            mountId,
            moduleId
        );
    }

    public List<String> findOutfitPresetTeamTags(String classTag) {
        return queryForList(
            """
                SELECT team_tag
                FROM outfit_preset_rules
                WHERE class_tag = ?
                ORDER BY team_tag
                """,
            String.class,
            classTag
        );
    }

    public void insertDefaultOutfitPreset(
        UUID playerId,
        String teamTag,
        String classTag,
        int outfitPresetSlot,
        long catalogVersion,
        OffsetDateTime now
    ) {
        update(
            """
                INSERT INTO player_outfit_presets(
                  player_id,
                  team_tag,
                  class_tag,
                  outfit_preset_slot,
                  catalog_version,
                  revision,
                  sanitized,
                  updated_at
                )
                VALUES (?, ?, ?, ?, ?, 1, false, ?)
                """,
            playerId,
            teamTag,
            classTag,
            outfitPresetSlot,
            catalogVersion,
            now
        );
    }

    public void insertDefaultOutfitPresetItem(
        UUID playerId,
        String teamTag,
        String classTag,
        int outfitPresetSlot,
        long catalogVersion,
        String clothingSlotId,
        String itemId
    ) {
        update(
            """
                INSERT INTO player_outfit_preset_items(
                  player_id,
                  team_tag,
                  class_tag,
                  outfit_preset_slot,
                  catalog_version,
                  clothing_slot_id,
                  item_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
            playerId,
            teamTag,
            classTag,
            outfitPresetSlot,
            catalogVersion,
            clothingSlotId,
            itemId
        );
    }
}
