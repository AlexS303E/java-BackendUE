package com.game.backend.matchprofile.application;

import com.game.backend.common.api.ApiException;
import com.game.backend.matchprofile.api.BuildMatchProfileRequest;
import com.game.backend.matchprofile.api.DependencyRevisionsDto;
import com.game.backend.matchprofile.api.MatchProfileResponse;
import com.game.backend.serverauth.application.ServerAuditService;
import com.game.backend.serverauth.application.ServerIdentity;
import com.game.backend.serverauth.application.ServerMatchService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Собирает server-ready match profile из presets, access projection и правил каталога.
 */
@Service
public class MatchProfileService {
    private static final String AUDIT_ACTION = "match_profile.build";
    private static final String AUDIT_SCOPE = "match_profile:read";

    private final ServerMatchService serverMatchService;
    private final ServerAuditService serverAuditService;
    private final CatalogVersionSelector catalogVersionSelector;
    private final MatchProfileCacheService matchProfileCacheService;
    private final MatchProfileSnapshotBuilder snapshotBuilder;
    private final MatchProfileDependencyService dependencyService;

    public MatchProfileService(
        ServerMatchService serverMatchService,
        ServerAuditService serverAuditService,
        CatalogVersionSelector catalogVersionSelector,
        MatchProfileCacheService matchProfileCacheService,
        MatchProfileSnapshotBuilder snapshotBuilder,
        MatchProfileDependencyService dependencyService
    ) {
        this.serverMatchService = serverMatchService;
        this.serverAuditService = serverAuditService;
        this.catalogVersionSelector = catalogVersionSelector;
        this.matchProfileCacheService = matchProfileCacheService;
        this.snapshotBuilder = snapshotBuilder;
        this.dependencyService = dependencyService;
    }

    @Transactional
    public MatchProfileResponse build(ServerIdentity server, BuildMatchProfileRequest request) {
        boolean matchAssigned = false;
        try {
            serverMatchService.ensureAssignedForBuild(server, request);
            matchAssigned = true;

            long catalogVersion = catalogVersionSelector.select(request);

            MatchProfileDependencyService.DependencyTuple dependencies = dependencyService.load(request, catalogVersion);

            MatchProfileResponse existing = matchProfileCacheService.findByDependencyTuple(
                request, catalogVersion,
                dependencies.weaponPresetRevision(), dependencies.outfitPresetRevision(), dependencies.accessRevision()
            );
            if (existing != null) {
                return existing;
            }

            long profileRevision = System.currentTimeMillis();
            MatchProfileSnapshotBuilder.Snapshot snapshot = snapshotBuilder.build(request, catalogVersion);

            MatchProfileResponse response = new MatchProfileResponse(
                1,
                request.playerId(),
                request.realmId(),
                catalogVersion,
                request.classTag(),
                request.teamTag(),
                request.weaponPresetSlot(),
                request.outfitPresetSlot(),
                snapshot.weapons(),
                snapshot.outfit(),
                snapshot.warnings(),
                new DependencyRevisionsDto(
                    dependencies.weaponPresetRevision(),
                    dependencies.outfitPresetRevision(),
                    dependencies.accessRevision(),
                    profileRevision
                )
            );
            matchProfileCacheService.save(request, response);
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
        serverAuditService.recordSync(
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
                "game_mode_id", request.gameModeId(),
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
                "game_mode_id", request.gameModeId(),
                "code", code,
                "status", status
            )
        );
    }

    private String auditResult(ApiException exception) {
        return exception.status() == HttpStatus.FORBIDDEN ? "denied" : "failed";
    }
}
