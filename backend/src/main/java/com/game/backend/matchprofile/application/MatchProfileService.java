package com.game.backend.matchprofile.application;

import com.game.backend.common.api.ApiException;
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
    public MatchProfileResponse build(ServerIdentity server, MatchProfileBuildCommand command) {
        boolean matchAssigned = false;
        try {
            serverMatchService.ensureAssignedForBuild(server, command);
            matchAssigned = true;

            long catalogVersion = catalogVersionSelector.select(command);

            MatchProfileDependencyService.DependencyTuple dependencies = dependencyService.load(command, catalogVersion);

            MatchProfileResponse existing = matchProfileCacheService.findByDependencyTuple(
                command, catalogVersion,
                dependencies.weaponPresetRevision(), dependencies.outfitPresetRevision(), dependencies.accessRevision()
            );
            if (existing != null) {
                return existing;
            }

            long profileRevision = System.currentTimeMillis();
            MatchProfileSnapshotBuilder.Snapshot snapshot = snapshotBuilder.build(command, catalogVersion);

            MatchProfileResponse response = new MatchProfileResponse(
                1,
                command.playerId(),
                command.realmId(),
                catalogVersion,
                command.classTag(),
                command.teamTag(),
                command.weaponPresetSlot(),
                command.outfitPresetSlot(),
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
            matchProfileCacheService.save(command, response);
            auditSuccess(server, command, response);
            return response;
        } catch (ApiException exception) {
            auditFailure(server, command, matchAssigned, auditResult(exception), exception.code(), exception.status().value());
            throw exception;
        } catch (RuntimeException exception) {
            auditFailure(server, command, matchAssigned, "failed", exception.getClass().getSimpleName(), 500);
            throw exception;
        }
    }

    private void auditSuccess(ServerIdentity server, MatchProfileBuildCommand command, MatchProfileResponse response) {
        serverAuditService.recordSync(
            server,
            command.matchId(),
            AUDIT_ACTION,
            AUDIT_SCOPE,
            "success",
            Map.of(
                "match_id", command.matchId(),
                "player_id", command.playerId(),
                "realm_id", command.realmId(),
                "class_tag", command.classTag(),
                "team_tag", command.teamTag(),
                "game_mode_id", command.gameModeId(),
                "catalog_version", response.catalogVersion(),
                "weapon_preset_revision", response.dependencyRevisions().weaponPresetRevision(),
                "outfit_preset_revision", response.dependencyRevisions().outfitPresetRevision()
            )
        );
    }

    private void auditFailure(
        ServerIdentity server,
        MatchProfileBuildCommand command,
        boolean matchAssigned,
        String result,
        String code,
        int status
    ) {
        serverAuditService.record(
            server,
            matchAssigned ? command.matchId() : null,
            AUDIT_ACTION,
            AUDIT_SCOPE,
            result,
            Map.of(
                "match_id", command.matchId(),
                "player_id", command.playerId(),
                "realm_id", command.realmId(),
                "class_tag", command.classTag(),
                "team_tag", command.teamTag(),
                "game_mode_id", command.gameModeId(),
                "code", code,
                "status", status
            )
        );
    }

    private String auditResult(ApiException exception) {
        return exception.status() == HttpStatus.FORBIDDEN ? "denied" : "failed";
    }
}
