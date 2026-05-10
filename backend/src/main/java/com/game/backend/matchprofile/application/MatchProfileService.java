package com.game.backend.matchprofile.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.catalog.application.CatalogService;
import com.game.backend.catalog.application.CatalogValidationData;
import com.game.backend.common.api.ApiException;
import com.game.backend.matchprofile.api.BuildMatchProfileRequest;
import com.game.backend.matchprofile.api.DependencyRevisionsDto;
import com.game.backend.matchprofile.api.MatchModuleDto;
import com.game.backend.matchprofile.api.MatchOutfitItemDto;
import com.game.backend.matchprofile.api.MatchProfileResponse;
import com.game.backend.matchprofile.api.MatchWeaponDto;
import com.game.backend.serverauth.application.ServerAuditService;
import com.game.backend.serverauth.application.ServerIdentity;
import com.game.backend.serverauth.application.ServerMatchService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Собирает server-ready match profile из presets, access projection и правил каталога.
 */
@Service
public class MatchProfileService {
    private static final String AUDIT_ACTION = "match_profile.build";
    private static final String AUDIT_SCOPE = "match_profile:read";

    private final JdbcTemplate jdbcTemplate;
    private final CatalogService catalogService;
    private final CatalogValidationData catalogValidationData;
    private final ObjectMapper objectMapper;
    private final ServerMatchService serverMatchService;
    private final ServerAuditService serverAuditService;

    public MatchProfileService(
        JdbcTemplate jdbcTemplate,
        CatalogService catalogService,
        CatalogValidationData catalogValidationData,
        ObjectMapper objectMapper,
        ServerMatchService serverMatchService,
        ServerAuditService serverAuditService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.catalogService = catalogService;
        this.catalogValidationData = catalogValidationData;
        this.objectMapper = objectMapper;
        this.serverMatchService = serverMatchService;
        this.serverAuditService = serverAuditService;
    }

    /**
     * Привязывает матч к DS, выбирает совместимую версию каталога и сохраняет snapshot профиля.
     */
    @Transactional
    public MatchProfileResponse build(ServerIdentity server, BuildMatchProfileRequest request) {
        boolean matchAssigned = false;
        try {
            serverMatchService.ensureAssignedForBuild(server, request);
            matchAssigned = true;

            long catalogVersion = chooseCatalogVersion(request);


            MatchProfileResponse existing = findExistingProfile(request, catalogVersion);
            if (existing != null) {
                return existing;
            }

            PresetHeader weaponPreset = weaponPreset(request, catalogVersion);
            PresetHeader outfitPreset = outfitPreset(request, catalogVersion);
            long accessRevision = accessRevision(request.playerId());
            long profileRevision = System.currentTimeMillis();

            List<MatchWeaponDto> weapons = weapons(request, catalogVersion);
            List<MatchOutfitItemDto> outfit = outfit(request, catalogVersion);
            validateLoadout(request, catalogVersion, weapons, outfit);

            MatchProfileResponse response = new MatchProfileResponse(
                1,
                request.playerId(),
                request.realmId(),
                catalogVersion,
                request.classTag(),
                request.teamTag(),
                request.weaponPresetSlot(),
                request.outfitPresetSlot(),
                weapons,
                outfit,
                List.of(),
                new DependencyRevisionsDto(
                    weaponPreset.revision(),
                    outfitPreset.revision(),
                    accessRevision,
                    profileRevision
                )
            );
            persistProfile(request, response);
            auditSuccess(server, request, response);
            return response;
        } catch (ApiException exception) {
            auditFailure(server, request, matchAssigned, auditResult(exception), exception.code(), exception.status().value());
            throw exception;
        } catch (RuntimeException exception) {
            auditFailure(server, request, matchAssigned, "failed", exception.getClass().getSimpleName(), 500);
            throw exception;
        }
    }

    private void auditSuccess(ServerIdentity server, BuildMatchProfileRequest request, MatchProfileResponse response) {
        serverAuditService.record(
            server,
            request.matchId(),
            AUDIT_ACTION,
            AUDIT_SCOPE,
            "success",
            Map.of(
                "match_id", request.matchId(),
                "player_id", request.playerId(),
                "realm_id", request.realmId(),
                "class_tag", request.classTag(),
                "team_tag", request.teamTag(),
                "catalog_version", response.catalogVersion(),
                "weapon_preset_revision", response.dependencyRevisions().weaponPresetRevision(),
                "outfit_preset_revision", response.dependencyRevisions().outfitPresetRevision()
            )
        );
    }

    private void auditFailure(
        ServerIdentity server,
        BuildMatchProfileRequest request,
        boolean matchAssigned,
        String result,
        String code,
        int status
    ) {
        serverAuditService.record(
            server,
            matchAssigned ? request.matchId() : null,
            AUDIT_ACTION,
            AUDIT_SCOPE,
            result,
            Map.of(
                "match_id", request.matchId(),
                "player_id", request.playerId(),
                "realm_id", request.realmId(),
                "class_tag", request.classTag(),
                "team_tag", request.teamTag(),
                "code", code,
                "status", status
            )
        );
    }

    private String auditResult(ApiException exception) {
        return exception.status() == HttpStatus.FORBIDDEN ? "denied" : "failed";
    }

    private MatchProfileResponse findExistingProfile(BuildMatchProfileRequest request, long catalogVersion) {
        List<String> payloads = jdbcTemplate.queryForList(
            """
                SELECT payload
                FROM player_match_profiles
                WHERE player_id = ?
                  AND realm_id = ?
                  AND class_tag = ?
                  AND team_tag = ?
                  AND weapon_preset_slot = ?
                  AND outfit_preset_slot = ?
                  AND catalog_version = ?
                  AND is_stale = false
                  AND expires_at > NOW()
                ORDER BY generated_at DESC
                LIMIT 1
                """,
            String.class,
            request.playerId(),
            request.realmId(),
            request.classTag(),
            request.teamTag(),
            request.weaponPresetSlot(),
            request.outfitPresetSlot(),
            catalogVersion
        );
        if (payloads.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(payloads.getFirst(), MatchProfileResponse.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Выбирает лучшую версию каталога из списка, который поддерживает Dedicated Server.
     */
    private long chooseCatalogVersion(BuildMatchProfileRequest request) {
        return request.supportedCatalogVersions()
            .stream()
            .filter(version -> catalogService.catalogVersionAllowsNewMatches(request.realmId(), version))
            .sorted(preferredFirst(request.preferredCatalogVersion()))
            .findFirst()
            .orElseThrow(() -> new ApiException(
                HttpStatus.CONFLICT,
                "CATALOG_VERSION_NOT_SUPPORTED",
                "Dedicated Server does not support an active catalog version for realm " + request.realmId()
            ));
    }

    private Comparator<Long> preferredFirst(Long preferredCatalogVersion) {
        return (left, right) -> {
            if (preferredCatalogVersion == null) {
                return Long.compare(right, left);
            }
            if (left.equals(preferredCatalogVersion)) {
                return -1;
            }
            if (right.equals(preferredCatalogVersion)) {
                return 1;
            }
            return Long.compare(right, left);
        };
    }

    private PresetHeader weaponPreset(BuildMatchProfileRequest request, long catalogVersion) {
        List<PresetHeader> presets = jdbcTemplate.query(
            """
                SELECT revision, sanitized
                FROM player_weapon_presets
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                  AND catalog_version = ?
                """,
            (rs, rowNum) -> new PresetHeader(rs.getLong("revision"), rs.getBoolean("sanitized")),
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot(),
            catalogVersion
        );
        if (presets.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEAPON_PRESET_NOT_FOUND", "Weapon preset was not found for selected catalog version");
        }
        return presets.getFirst();
    }

    private PresetHeader outfitPreset(BuildMatchProfileRequest request, long catalogVersion) {
        List<PresetHeader> presets = jdbcTemplate.query(
            """
                SELECT revision, sanitized
                FROM player_outfit_presets
                WHERE player_id = ?
                  AND team_tag = ?
                  AND class_tag = ?
                  AND outfit_preset_slot = ?
                  AND catalog_version = ?
                """,
            (rs, rowNum) -> new PresetHeader(rs.getLong("revision"), rs.getBoolean("sanitized")),
            request.playerId(),
            request.teamTag(),
            request.classTag(),
            request.outfitPresetSlot(),
            catalogVersion
        );
        if (presets.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OUTFIT_PRESET_NOT_FOUND", "Outfit preset was not found for selected team and catalog version");
        }
        return presets.getFirst();
    }

    private long accessRevision(UUID playerId) {
        List<Long> revisions = jdbcTemplate.queryForList(
            "SELECT access_revision FROM player_access_projection_state WHERE player_id = ?",
            Long.class,
            playerId
        );
        if (revisions.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCESS_PROJECTION_NOT_FOUND", "Access projection was not found");
        }
        return revisions.getFirst();
    }

    private List<MatchWeaponDto> weapons(BuildMatchProfileRequest request, long catalogVersion) {
        List<Object[]> rows = jdbcTemplate.query(
            """
                SELECT ws.weapon_slot_id, ws.selected_weapon_id,
                       wcm.mount_id, wcm.module_id
                FROM player_weapon_preset_slots ws
                LEFT JOIN player_weapon_preset_weapon_config_modules wcm
                  ON  wcm.player_id = ws.player_id
                  AND wcm.class_tag = ws.class_tag
                  AND wcm.preset_slot = ws.preset_slot
                  AND wcm.catalog_version = ws.catalog_version
                  AND wcm.weapon_slot_id = ws.weapon_slot_id
                WHERE ws.player_id = ?
                  AND ws.class_tag = ?
                  AND ws.preset_slot = ?
                  AND ws.catalog_version = ?
                ORDER BY ws.weapon_slot_id, wcm.mount_id
                """,
            (rs, rowNum) -> new Object[]{
                rs.getString("weapon_slot_id"),
                rs.getString("selected_weapon_id"),
                rs.getString("mount_id"),
                rs.getString("module_id")
            },
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot(),
            catalogVersion
        );

        Map<String, MatchWeaponDto> weaponMap = new HashMap<>();
        for (Object[] row : rows) {
            String slotId = (String) row[0];
            String weaponId = (String) row[1];
            String mountId = (String) row[2];
            String moduleId = (String) row[3];

            MatchWeaponDto weapon = weaponMap.get(slotId);
            if (weapon == null) {
                weapon = new MatchWeaponDto(slotId, weaponId, new ArrayList<>());
                weaponMap.put(slotId, weapon);
            }
            if (mountId != null && moduleId != null) {
                weapon.modules().add(new MatchModuleDto(mountId, moduleId));
            }
        }

        return new ArrayList<>(weaponMap.values());
    }

    private List<MatchOutfitItemDto> outfit(BuildMatchProfileRequest request, long catalogVersion) {
        return jdbcTemplate.query(
            """
                SELECT clothing_slot_id, item_id
                FROM player_outfit_preset_items
                WHERE player_id = ?
                  AND team_tag = ?
                  AND class_tag = ?
                  AND outfit_preset_slot = ?
                  AND catalog_version = ?
                ORDER BY clothing_slot_id
                """,
            (rs, rowNum) -> new MatchOutfitItemDto(
                rs.getString("clothing_slot_id"),
                rs.getString("item_id")
            ),
            request.playerId(),
            request.teamTag(),
            request.classTag(),
            request.outfitPresetSlot(),
            catalogVersion
        );
    }

    /**
     * Проверяет, что все выбранные предметы доступны игроку и разрешены для class/team/mount.
     * Использует batch-проверки вместо N+1.
     */
    private void validateLoadout(
        BuildMatchProfileRequest request,
        long catalogVersion,
        List<MatchWeaponDto> weapons,
        List<MatchOutfitItemDto> outfit
    ) {
        Set<String> weaponSlotIds = new HashSet<>();
        Set<String> itemIds = new HashSet<>();
        Set<String> clothingSlotIds = new HashSet<>();
        List<ModuleMountPair> moduleMountPairs = new ArrayList<>();

        for (MatchWeaponDto weapon : weapons) {
            weaponSlotIds.add(weapon.weaponSlotId());
            if (weapon.weaponId() == null) continue;
            itemIds.add(weapon.weaponId());
            for (MatchModuleDto module : weapon.modules()) {
                itemIds.add(module.moduleId());
                moduleMountPairs.add(new ModuleMountPair(module.mountId(), module.moduleId()));
            }
        }

        for (MatchOutfitItemDto item : outfit) {
            clothingSlotIds.add(item.clothingSlotId());
            itemIds.add(item.itemId());
        }

        validateWeaponSlotsAllowedBatch(request.classTag(), weaponSlotIds);
        validateCanUseBatch(request, catalogVersion, itemIds);
        validateMountModulesAllowedBatch(catalogVersion, moduleMountPairs);
        validateClothingSlotsBatch(clothingSlotIds);
    }

    private void validateWeaponSlotsAllowedBatch(String classTag, Set<String> weaponSlotIds) {
        if (weaponSlotIds.isEmpty()) return;
        Map<String, Boolean> rules = catalogValidationData.getWeaponSlotRules(classTag);
        for (String slotId : weaponSlotIds) {
            if (!Boolean.TRUE.equals(rules.get(slotId))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LOADOUT_VALIDATION_FAILED", "Weapon slot is not allowed for class: " + slotId);
            }
        }
    }

    private void validateCanUseBatch(BuildMatchProfileRequest request, long catalogVersion, Set<String> itemIds) {
        if (itemIds.isEmpty()) return;
        String placeholders = String.join(",", itemIds.stream().map(id -> "?").toArray(String[]::new));
        Object[] params = new Object[4 + itemIds.size()];
        params[0] = request.playerId();
        params[1] = catalogVersion;
        int i = 2;
        for (String id : itemIds) {
            params[i++] = id;
        }
        params[i++] = request.classTag();
        params[i] = request.teamTag();
        List<String> usableItems = jdbcTemplate.queryForList(
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
                  AND ci.item_id IN (""" + placeholders + """
                )
                  AND EXISTS (
                    SELECT 1 FROM item_class_rules icr
                    WHERE icr.item_id = ci.item_id
                      AND icr.catalog_version = ci.catalog_version
                      AND icr.class_tag = ?
                      AND icr.rule_effect = 'allow'
                  )
                  AND EXISTS (
                    SELECT 1 FROM item_team_rules itr
                    WHERE itr.item_id = ci.item_id
                      AND itr.catalog_version = ci.catalog_version
                      AND (itr.team_scope = 'all' OR (itr.team_scope = 'specific' AND itr.team_tag = ?))
                  )
                """,
            String.class,
            params
        );
        for (String itemId : itemIds) {
            if (!usableItems.contains(itemId)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LOADOUT_VALIDATION_FAILED", "Item is not usable in selected loadout: " + itemId);
            }
        }
    }

    private void validateMountModulesAllowedBatch(long catalogVersion, List<ModuleMountPair> pairs) {
        if (pairs.isEmpty()) return;
        Map<String, Set<String>> allowedByMount = catalogValidationData.getMountAllowedModules(catalogVersion);
        for (ModuleMountPair p : pairs) {
            Set<String> modules = allowedByMount.get(p.mountId);
            if (modules == null || !modules.contains(p.moduleId)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LOADOUT_VALIDATION_FAILED", "Module is not allowed for mount: " + p.moduleId);
            }
        }
    }

    private void validateClothingSlotsBatch(Set<String> clothingSlotIds) {
        if (clothingSlotIds.isEmpty()) return;
        Set<String> activeSlots = catalogValidationData.getActiveClothingSlots();
        for (String slotId : clothingSlotIds) {
            if (!activeSlots.contains(slotId)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LOADOUT_VALIDATION_FAILED", "Clothing slot is not active: " + slotId);
            }
        }
    }

    private record ModuleMountPair(String mountId, String moduleId) {}

    /**
     * Сохраняет сгенерированный профиль как кэшируемый snapshot с ревизиями зависимостей.
     */
    private void persistProfile(BuildMatchProfileRequest request, MatchProfileResponse response) {
        OffsetDateTime now = OffsetDateTime.now();
        String payload = toJson(response);

        jdbcTemplate.update(
            """
                INSERT INTO player_match_profiles(
                  profile_id,
                  player_id,
                  realm_id,
                  class_tag,
                  team_tag,
                  weapon_preset_slot,
                  outfit_preset_slot,
                  weapon_preset_revision,
                  outfit_preset_revision,
                  access_revision,
                  catalog_version,
                  profile_revision,
                  payload,
                  payload_schema_version,
                  is_stale,
                  generated_at,
                  expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 1, false, ?, ?)
                ON CONFLICT (
                  player_id,
                  realm_id,
                  class_tag,
                  team_tag,
                  weapon_preset_slot,
                  outfit_preset_slot,
                  weapon_preset_revision,
                  outfit_preset_revision,
                  access_revision,
                  catalog_version
                )
                DO UPDATE SET
                  profile_revision = EXCLUDED.profile_revision,
                  payload = EXCLUDED.payload,
                  payload_schema_version = EXCLUDED.payload_schema_version,
                  is_stale = false,
                  stale_reason = null,
                  stale_at = null,
                  generated_at = EXCLUDED.generated_at,
                  expires_at = EXCLUDED.expires_at
                """,
            UUID.randomUUID(),
            request.playerId(),
            response.realmId(),
            response.classTag(),
            response.teamTag(),
            response.weaponPresetSlot(),
            response.outfitPresetSlot(),
            response.dependencyRevisions().weaponPresetRevision(),
            response.dependencyRevisions().outfitPresetRevision(),
            response.dependencyRevisions().accessRevision(),
            response.catalogVersion(),
            response.dependencyRevisions().profileRevision(),
            payload,
            now,
            now.plusMinutes(10)
        );
    }

    private String toJson(MatchProfileResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MATCH_PROFILE_SERIALIZATION_FAILED", "Unable to serialize match profile");
        }
    }

    private record PresetHeader(long revision, boolean sanitized) {
    }
}
