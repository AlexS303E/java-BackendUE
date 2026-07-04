package com.game.backend.admin.api;

import com.game.backend.admin.application.AdminItemOperationService;
import com.game.backend.admin.application.CurrentAdmin;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Явные admin item endpoints из ТЗ.
 */
@RestController
public class AdminItemOperationsController {
    private final AdminItemOperationService adminItemOperationService;

    public AdminItemOperationsController(AdminItemOperationService adminItemOperationService) {
        this.adminItemOperationService = adminItemOperationService;
    }

    @PostMapping("/admin/items/hide")
    AdminItemAccessUpdateResponse hide(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AdminItemOperationRequest request
    ) {
        return AdminItemAccessApiMapper.toResponse(adminItemOperationService.hide(CurrentAdmin.require(authentication), idempotencyKey, request));
    }

    @PostMapping("/admin/items/reveal")
    AdminItemAccessUpdateResponse reveal(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AdminItemOperationRequest request
    ) {
        return AdminItemAccessApiMapper.toResponse(adminItemOperationService.reveal(CurrentAdmin.require(authentication), idempotencyKey, request));
    }

    @PostMapping("/admin/items/shop-lock")
    AdminItemAccessUpdateResponse shopLock(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AdminItemOperationRequest request
    ) {
        return AdminItemAccessApiMapper.toResponse(adminItemOperationService.shopLock(CurrentAdmin.require(authentication), idempotencyKey, request));
    }

    @PostMapping("/admin/items/shop-unlock")
    AdminItemAccessUpdateResponse shopUnlock(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AdminItemOperationRequest request
    ) {
        return AdminItemAccessApiMapper.toResponse(adminItemOperationService.shopUnlock(CurrentAdmin.require(authentication), idempotencyKey, request));
    }

    @PostMapping("/admin/items/quest-lock")
    AdminItemAccessUpdateResponse questLock(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AdminItemOperationRequest request
    ) {
        return AdminItemAccessApiMapper.toResponse(adminItemOperationService.questLock(CurrentAdmin.require(authentication), idempotencyKey, request));
    }

    @PostMapping("/admin/items/quest-unlock")
    AdminItemAccessUpdateResponse questUnlock(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AdminItemOperationRequest request
    ) {
        return AdminItemAccessApiMapper.toResponse(adminItemOperationService.questUnlock(CurrentAdmin.require(authentication), idempotencyKey, request));
    }

    @PostMapping("/admin/items/disable")
    AdminItemAccessUpdateResponse disable(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AdminItemOperationRequest request
    ) {
        return AdminItemAccessApiMapper.toResponse(adminItemOperationService.disable(CurrentAdmin.require(authentication), idempotencyKey, request));
    }

    @PostMapping("/admin/items/enable")
    AdminItemAccessUpdateResponse enable(
        Authentication authentication,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AdminItemOperationRequest request
    ) {
        return AdminItemAccessApiMapper.toResponse(adminItemOperationService.enable(CurrentAdmin.require(authentication), idempotencyKey, request));
    }
}
