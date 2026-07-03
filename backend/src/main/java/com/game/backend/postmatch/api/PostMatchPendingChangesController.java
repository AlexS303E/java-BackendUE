package com.game.backend.postmatch.api;

import com.game.backend.auth.application.CurrentPlayer;
import com.game.backend.postmatch.application.PostMatchPendingChangeEntry;
import com.game.backend.postmatch.application.PostMatchPendingChangePage;
import com.game.backend.postmatch.application.PostMatchPendingChangeResolution;
import com.game.backend.postmatch.application.PostMatchPendingChangesService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Player API для просмотра и решения post-match pending changes.
 */
@RestController
public class PostMatchPendingChangesController {
    private final PostMatchPendingChangesService postMatchPendingChangesService;

    public PostMatchPendingChangesController(PostMatchPendingChangesService postMatchPendingChangesService) {
        this.postMatchPendingChangesService = postMatchPendingChangesService;
    }

    /**
     * Возвращает pending changes текущего игрока. По умолчанию показывает только status=pending.
     */
    @GetMapping("/me/post-match-pending-changes")
    PostMatchPendingChangesResponse getMyPendingChanges(
            Authentication authentication,
            @RequestParam(value = "status", defaultValue = "pending") String status
    ) {
        UUID playerId = CurrentPlayer.require(authentication).playerId();
        return toResponse(postMatchPendingChangesService.getChanges(playerId, status));
    }

    /**
     * Применяет выбранное игроком решение: apply_if_still_valid или discard.
     */
    @PostMapping("/me/post-match-pending-changes/{change_id}/resolve")
    PostMatchPendingChangeResolutionResponse resolvePendingChange(
            Authentication authentication,
            @PathVariable("change_id") UUID changeId,
            @Valid @RequestBody PostMatchPendingChangeResolutionRequest request
    ) {
        UUID playerId = CurrentPlayer.require(authentication).playerId();
        return toResponse(postMatchPendingChangesService.resolve(playerId, changeId, request.resolution()));
    }

    private PostMatchPendingChangesResponse toResponse(PostMatchPendingChangePage page) {
        return new PostMatchPendingChangesResponse(
            page.playerId(),
            page.changes().stream().map(this::toDto).toList()
        );
    }

    private PostMatchPendingChangeDto toDto(PostMatchPendingChangeEntry change) {
        return new PostMatchPendingChangeDto(
            change.changeId(),
            change.matchId(),
            change.classTag(),
            change.weaponPresetSlot(),
            change.baseWeaponPresetRevision(),
            change.currentConflictingRevision(),
            change.reasonCode(),
            change.status(),
            change.payload(),
            change.createdAt(),
            change.expiresAt(),
            change.resolvedAt()
        );
    }

    private PostMatchPendingChangeResolutionResponse toResponse(PostMatchPendingChangeResolution resolution) {
        return new PostMatchPendingChangeResolutionResponse(
            resolution.changeId(),
            resolution.status(),
            resolution.resultRevision(),
            resolution.resolvedAt()
        );
    }
}
