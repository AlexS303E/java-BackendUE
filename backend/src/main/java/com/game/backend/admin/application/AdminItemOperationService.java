package com.game.backend.admin.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.admin.api.AdminItemAccessUpdateRequest;
import com.game.backend.admin.api.AdminItemAccessUpdateResponse;
import com.game.backend.admin.api.AdminItemOperationRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * ТЗ-совместимые item mutation commands поверх единого AdminPlayerAccessService.
 */
@Service
public class AdminItemOperationService {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AdminPlayerAccessService adminPlayerAccessService;
    private final AdminMutationIdempotencyService idempotencyService;

    public AdminItemOperationService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        AdminPlayerAccessService adminPlayerAccessService,
        AdminMutationIdempotencyService idempotencyService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.adminPlayerAccessService = adminPlayerAccessService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * Скрывает предмет для игрока.
     */
    public AdminItemAccessUpdateResponse hide(AdminIdentity admin, String idempotencyKey, AdminItemOperationRequest request) {
        return apply(admin, idempotencyKey, ItemOperation.HIDE, request);
    }

    /**
     * Снимает hidden-флаг с предмета для игрока.
     */
    public AdminItemAccessUpdateResponse reveal(AdminIdentity admin, String idempotencyKey, AdminItemOperationRequest request) {
        return apply(admin, idempotencyKey, ItemOperation.REVEAL, request);
    }

    /**
     * Ставит shop lock для игрока.
     */
    public AdminItemAccessUpdateResponse shopLock(AdminIdentity admin, String idempotencyKey, AdminItemOperationRequest request) {
        return apply(admin, idempotencyKey, ItemOperation.SHOP_LOCK, request);
    }

    /**
     * Снимает shop lock для игрока.
     */
    public AdminItemAccessUpdateResponse shopUnlock(AdminIdentity admin, String idempotencyKey, AdminItemOperationRequest request) {
        return apply(admin, idempotencyKey, ItemOperation.SHOP_UNLOCK, request);
    }

    /**
     * Ставит quest lock для игрока.
     */
    public AdminItemAccessUpdateResponse questLock(AdminIdentity admin, String idempotencyKey, AdminItemOperationRequest request) {
        return apply(admin, idempotencyKey, ItemOperation.QUEST_LOCK, request);
    }

    /**
     * Снимает quest lock для игрока.
     */
    public AdminItemAccessUpdateResponse questUnlock(AdminIdentity admin, String idempotencyKey, AdminItemOperationRequest request) {
        return apply(admin, idempotencyKey, ItemOperation.QUEST_UNLOCK, request);
    }

    /**
     * Отключает предмет для конкретного игрока через access projection.
     */
    public AdminItemAccessUpdateResponse disable(AdminIdentity admin, String idempotencyKey, AdminItemOperationRequest request) {
        return apply(admin, idempotencyKey, ItemOperation.DISABLE, request);
    }

    /**
     * Снимает player-level disable с предмета.
     */
    public AdminItemAccessUpdateResponse enable(AdminIdentity admin, String idempotencyKey, AdminItemOperationRequest request) {
        return apply(admin, idempotencyKey, ItemOperation.ENABLE, request);
    }

    private AdminItemAccessUpdateResponse apply(
        AdminIdentity admin,
        String idempotencyKey,
        ItemOperation operation,
        AdminItemOperationRequest request
    ) {
        return idempotencyService.execute(
            admin,
            "admin.item." + operation.routeName(),
            "/admin/items/" + operation.routeName(),
            idempotencyKey,
            request,
            AdminItemAccessUpdateResponse.class,
            () -> applyWithoutReplay(admin, idempotencyKey, operation, request)
        );
    }

    @Transactional
    protected AdminItemAccessUpdateResponse applyWithoutReplay(
        AdminIdentity admin,
        String externalIdempotencyKey,
        ItemOperation operation,
        AdminItemOperationRequest request
    ) {
        AccessFlags current = currentFlags(request);
        AccessFlags updated = operation.apply(current, request);
        AdminItemAccessUpdateRequest updateRequest = new AdminItemAccessUpdateRequest(
            request.catalogVersion(),
            updated.hidden(),
            updated.lockedInShop(),
            updated.lockedByQuest(),
            updated.disabled(),
            updated.disabledReason(),
            updated.unlockHintCode(),
            updated.unlockHintPayload(),
            request.reason()
        );
        return adminPlayerAccessService.updateItemAccess(
            admin,
            internalIdempotencyKey(operation, externalIdempotencyKey),
            request.playerId(),
            request.itemId(),
            updateRequest
        );
    }

    private AccessFlags currentFlags(AdminItemOperationRequest request) {
        List<AccessFlags> rows = jdbcTemplate.query(
            """
                SELECT
                  is_hidden,
                  is_locked_in_shop,
                  is_locked_by_quest,
                  is_disabled,
                  disabled_reason,
                  unlock_hint_code,
                  unlock_hint_payload::text AS unlock_hint_payload
                FROM player_item_access
                WHERE player_id = ?
                  AND item_id = ?
                  AND catalog_version = ?
                """,
            (rs, rowNum) -> new AccessFlags(
                rs.getBoolean("is_hidden"),
                rs.getBoolean("is_locked_in_shop"),
                rs.getBoolean("is_locked_by_quest"),
                rs.getBoolean("is_disabled"),
                rs.getString("disabled_reason"),
                rs.getString("unlock_hint_code"),
                parsePayload(rs.getString("unlock_hint_payload"))
            ),
            request.playerId(),
            request.itemId(),
            request.catalogVersion()
        );
        if (rows.isEmpty()) {
            return AccessFlags.defaultAllow();
        }
        return rows.getFirst();
    }

    private String internalIdempotencyKey(ItemOperation operation, String externalIdempotencyKey) {
        return "admin-item:" + operation.routeName() + ":" + externalIdempotencyKey;
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JSON_MAP);
        } catch (JsonProcessingException exception) {
            return Map.of("parse_error", true);
        }
    }

    private record AccessFlags(
        boolean hidden,
        boolean lockedInShop,
        boolean lockedByQuest,
        boolean disabled,
        String disabledReason,
        String unlockHintCode,
        Map<String, Object> unlockHintPayload
    ) {
        static AccessFlags defaultAllow() {
            return new AccessFlags(false, false, false, false, null, null, null);
        }
    }

    private enum ItemOperation {
        HIDE("hide", "hidden") {
            @Override
            AccessFlags apply(AccessFlags flags, AdminItemOperationRequest request) {
                return new AccessFlags(
                    true,
                    flags.lockedInShop(),
                    flags.lockedByQuest(),
                    flags.disabled(),
                    flags.disabledReason(),
                    hint(request, defaultHint()),
                    payload(request, flags)
                );
            }
        },
        REVEAL("reveal", null) {
            @Override
            AccessFlags apply(AccessFlags flags, AdminItemOperationRequest request) {
                return normalize(new AccessFlags(
                    false,
                    flags.lockedInShop(),
                    flags.lockedByQuest(),
                    flags.disabled(),
                    flags.disabledReason(),
                    flags.unlockHintCode(),
                    flags.unlockHintPayload()
                ));
            }
        },
        SHOP_LOCK("shop-lock", "buy_in_shop") {
            @Override
            AccessFlags apply(AccessFlags flags, AdminItemOperationRequest request) {
                return new AccessFlags(
                    flags.hidden(),
                    true,
                    flags.lockedByQuest(),
                    flags.disabled(),
                    flags.disabledReason(),
                    hint(request, defaultHint()),
                    payload(request, flags)
                );
            }
        },
        SHOP_UNLOCK("shop-unlock", null) {
            @Override
            AccessFlags apply(AccessFlags flags, AdminItemOperationRequest request) {
                return normalize(new AccessFlags(
                    flags.hidden(),
                    false,
                    flags.lockedByQuest(),
                    flags.disabled(),
                    flags.disabledReason(),
                    flags.unlockHintCode(),
                    flags.unlockHintPayload()
                ));
            }
        },
        QUEST_LOCK("quest-lock", "complete_quest") {
            @Override
            AccessFlags apply(AccessFlags flags, AdminItemOperationRequest request) {
                return new AccessFlags(
                    flags.hidden(),
                    flags.lockedInShop(),
                    true,
                    flags.disabled(),
                    flags.disabledReason(),
                    hint(request, defaultHint()),
                    payload(request, flags)
                );
            }
        },
        QUEST_UNLOCK("quest-unlock", null) {
            @Override
            AccessFlags apply(AccessFlags flags, AdminItemOperationRequest request) {
                return normalize(new AccessFlags(
                    flags.hidden(),
                    flags.lockedInShop(),
                    false,
                    flags.disabled(),
                    flags.disabledReason(),
                    flags.unlockHintCode(),
                    flags.unlockHintPayload()
                ));
            }
        },
        DISABLE("disable", "admin_disabled") {
            @Override
            AccessFlags apply(AccessFlags flags, AdminItemOperationRequest request) {
                return new AccessFlags(
                    flags.hidden(),
                    flags.lockedInShop(),
                    flags.lockedByQuest(),
                    true,
                    firstNonBlank(request.disabledReason(), request.reason()),
                    hint(request, defaultHint()),
                    payload(request, flags)
                );
            }
        },
        ENABLE("enable", null) {
            @Override
            AccessFlags apply(AccessFlags flags, AdminItemOperationRequest request) {
                return normalize(new AccessFlags(
                    flags.hidden(),
                    flags.lockedInShop(),
                    flags.lockedByQuest(),
                    false,
                    null,
                    flags.unlockHintCode(),
                    flags.unlockHintPayload()
                ));
            }
        };

        private final String routeName;
        private final String defaultHint;

        ItemOperation(String routeName, String defaultHint) {
            this.routeName = routeName;
            this.defaultHint = defaultHint;
        }

        String routeName() {
            return routeName;
        }

        String defaultHint() {
            return defaultHint;
        }

        abstract AccessFlags apply(AccessFlags flags, AdminItemOperationRequest request);

        private static AccessFlags normalize(AccessFlags flags) {
            if (flags.hidden()) {
                return new AccessFlags(true, flags.lockedInShop(), flags.lockedByQuest(), flags.disabled(), flags.disabledReason(), "hidden", flags.unlockHintPayload());
            }
            if (flags.disabled()) {
                return new AccessFlags(false, flags.lockedInShop(), flags.lockedByQuest(), true, flags.disabledReason(), "admin_disabled", flags.unlockHintPayload());
            }
            if (flags.lockedInShop()) {
                return new AccessFlags(false, true, flags.lockedByQuest(), false, null, firstNonBlank(flags.unlockHintCode(), "buy_in_shop"), flags.unlockHintPayload());
            }
            if (flags.lockedByQuest()) {
                return new AccessFlags(false, false, true, false, null, firstNonBlank(flags.unlockHintCode(), "complete_quest"), flags.unlockHintPayload());
            }
            return new AccessFlags(false, false, false, false, null, null, null);
        }

        private static String hint(AdminItemOperationRequest request, String fallback) {
            return firstNonBlank(request.unlockHintCode(), fallback);
        }

        private static Map<String, Object> payload(AdminItemOperationRequest request, AccessFlags flags) {
            if (request.unlockHintPayload() != null) {
                return request.unlockHintPayload();
            }
            return flags.unlockHintPayload();
        }

        private static String firstNonBlank(String value, String fallback) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
            return fallback;
        }
    }
}
