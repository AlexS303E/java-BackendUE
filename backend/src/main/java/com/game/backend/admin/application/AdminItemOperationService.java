package com.game.backend.admin.application;

import com.game.backend.admin.repository.AdminRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.admin.api.AdminItemOperationRequest;
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

    private final AdminRepository repository;
    private final ObjectMapper objectMapper;
    private final AdminPlayerAccessService adminPlayerAccessService;
    private final AdminMutationIdempotencyService idempotencyService;

    public AdminItemOperationService(
        AdminRepository repository,
        ObjectMapper objectMapper,
        AdminPlayerAccessService adminPlayerAccessService,
        AdminMutationIdempotencyService idempotencyService
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.adminPlayerAccessService = adminPlayerAccessService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * Скрывает предмет для игрока.
     */
    public AdminItemAccessUpdateResult hide(AdminIdentity admin, String idempotencyKey, AdminItemOperationRequest request) {
        return apply(admin, idempotencyKey, ItemOperation.HIDE, request);
    }

    /**
     * Снимает hidden-флаг с предмета для игрока.
     */
    public AdminItemAccessUpdateResult reveal(AdminIdentity admin, String idempotencyKey, AdminItemOperationRequest request) {
        return apply(admin, idempotencyKey, ItemOperation.REVEAL, request);
    }

    /**
     * Ставит shop lock для игрока.
     */
    public AdminItemAccessUpdateResult shopLock(AdminIdentity admin, String idempotencyKey, AdminItemOperationRequest request) {
        return apply(admin, idempotencyKey, ItemOperation.SHOP_LOCK, request);
    }

    /**
     * Снимает shop lock для игрока.
     */
    public AdminItemAccessUpdateResult shopUnlock(AdminIdentity admin, String idempotencyKey, AdminItemOperationRequest request) {
        return apply(admin, idempotencyKey, ItemOperation.SHOP_UNLOCK, request);
    }

    /**
     * Ставит quest lock для игрока.
     */
    public AdminItemAccessUpdateResult questLock(AdminIdentity admin, String idempotencyKey, AdminItemOperationRequest request) {
        return apply(admin, idempotencyKey, ItemOperation.QUEST_LOCK, request);
    }

    /**
     * Снимает quest lock для игрока.
     */
    public AdminItemAccessUpdateResult questUnlock(AdminIdentity admin, String idempotencyKey, AdminItemOperationRequest request) {
        return apply(admin, idempotencyKey, ItemOperation.QUEST_UNLOCK, request);
    }

    /**
     * Отключает предмет для конкретного игрока через access projection.
     */
    public AdminItemAccessUpdateResult disable(AdminIdentity admin, String idempotencyKey, AdminItemOperationRequest request) {
        return apply(admin, idempotencyKey, ItemOperation.DISABLE, request);
    }

    /**
     * Снимает player-level disable с предмета.
     */
    public AdminItemAccessUpdateResult enable(AdminIdentity admin, String idempotencyKey, AdminItemOperationRequest request) {
        return apply(admin, idempotencyKey, ItemOperation.ENABLE, request);
    }

    private AdminItemAccessUpdateResult apply(
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
            AdminItemAccessUpdateResult.class,
            () -> applyWithoutReplay(admin, idempotencyKey, operation, request)
        );
    }

    @Transactional
    protected AdminItemAccessUpdateResult applyWithoutReplay(
        AdminIdentity admin,
        String externalIdempotencyKey,
        ItemOperation operation,
        AdminItemOperationRequest request
    ) {
        AccessFlags current = currentFlags(request);
        AccessFlags updated = operation.apply(current, request);
        AdminItemAccessUpdateCommand updateRequest = new AdminItemAccessUpdateCommand(
            request.catalogVersion(),
            updated.hidden(),
            updated.lockedInShop(),
            updated.lockedByQuest(),
            updated.disabled(),
            updated.disabledReason(),
            updated.unlockHintCode(),
            updated.unlockHintPayload(),
            request.reason(),
            operation.eventType()
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
        List<AdminRepository.ItemAccessFlags> rows = repository.findItemAccessFlags(
            request.playerId(),
            request.itemId(),
            request.catalogVersion()
        );
        if (rows.isEmpty()) {
            return AccessFlags.defaultAllow();
        }
        AdminRepository.ItemAccessFlags row = rows.getFirst();
        return new AccessFlags(
            row.hidden(),
            row.lockedInShop(),
            row.lockedByQuest(),
            row.disabled(),
            row.disabledReason(),
            row.unlockHintCode(),
            parsePayload(row.unlockHintPayloadJson())
        );
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
        HIDE("hide", "hidden", "hide_item") {
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
        REVEAL("reveal", null, "reveal_item") {
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
        SHOP_LOCK("shop-lock", "buy_in_shop", "shop_lock") {
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
        SHOP_UNLOCK("shop-unlock", null, "shop_unlock") {
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
        QUEST_LOCK("quest-lock", "complete_quest", "quest_lock") {
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
        QUEST_UNLOCK("quest-unlock", null, "quest_unlock") {
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
        DISABLE("disable", "admin_disabled", "item_disable") {
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
        ENABLE("enable", null, "item_enable") {
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
        private final String eventType;

        ItemOperation(String routeName, String defaultHint, String eventType) {
            this.routeName = routeName;
            this.defaultHint = defaultHint;
            this.eventType = eventType;
        }

        String routeName() {
            return routeName;
        }

        String eventType() {
            return eventType;
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
