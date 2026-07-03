package com.game.backend.matchprofile.application;

import com.game.backend.matchprofile.repository.MatchProfileRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.cache.RedisCacheService;
import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class MatchProfileCacheService {
    private final MatchProfileRepository repository;
    private final ObjectMapper objectMapper;
    private final RedisCacheService cacheService;

    public MatchProfileCacheService(
        MatchProfileRepository repository,
        ObjectMapper objectMapper,
        RedisCacheService cacheService
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.cacheService = cacheService;
    }

    public MatchProfileSnapshot findByDependencyTuple(
        MatchProfileBuildCommand command,
        long catalogVersion,
        long weaponPresetRevision,
        long outfitPresetRevision,
        long accessRevision
    ) {
        MatchProfileSnapshot cached = cacheService.getMatchProfile(
            command.playerId(),
            command.realmId(),
            command.classTag(),
            command.teamTag(),
            command.weaponPresetSlot(),
            command.outfitPresetSlot(),
            catalogVersion,
            weaponPresetRevision,
            outfitPresetRevision,
            accessRevision
        ).orElse(null);
        if (cached != null && matchesDependencyTuple(
            command,
            catalogVersion,
            weaponPresetRevision,
            outfitPresetRevision,
            accessRevision,
            cached
        )) {
            return cached;
        }

        List<String> payloads = repository.findFreshPayload(
            command.playerId(),
            command.realmId(),
            command.classTag(),
            command.teamTag(),
            command.weaponPresetSlot(),
            command.outfitPresetSlot(),
            catalogVersion,
            weaponPresetRevision,
            outfitPresetRevision,
            accessRevision
        );
        if (payloads.isEmpty()) {
            return null;
        }
        try {
            MatchProfileSnapshot response = objectMapper.readValue(payloads.getFirst(), MatchProfileSnapshot.class);
            if (matchesDependencyTuple(
                command,
                catalogVersion,
                weaponPresetRevision,
                outfitPresetRevision,
                accessRevision,
                response
            )) {
                cacheService.putMatchProfile(response);
                return response;
            }
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    public void save(MatchProfileBuildCommand command, MatchProfileSnapshot response) {
        OffsetDateTime now = OffsetDateTime.now();
        String payload = toJson(response);

        repository.saveProfile(
            UUID.randomUUID(),
            command.playerId(),
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
        cacheService.putMatchProfile(response);
    }

    private String toJson(MatchProfileSnapshot response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MATCH_PROFILE_SERIALIZATION_FAILED",
                "Unable to serialize match profile"
            );
        }
    }

    private boolean matchesDependencyTuple(
        MatchProfileBuildCommand command,
        long catalogVersion,
        long weaponPresetRevision,
        long outfitPresetRevision,
        long accessRevision,
        MatchProfileSnapshot response
    ) {
        return response != null
            && response.dependencyRevisions() != null
            && command.playerId().equals(response.playerId())
            && command.realmId().equals(response.realmId())
            && command.classTag().equals(response.classTag())
            && command.teamTag().equals(response.teamTag())
            && command.weaponPresetSlot() == response.weaponPresetSlot()
            && command.outfitPresetSlot() == response.outfitPresetSlot()
            && catalogVersion == response.catalogVersion()
            && weaponPresetRevision == response.dependencyRevisions().weaponPresetRevision()
            && outfitPresetRevision == response.dependencyRevisions().outfitPresetRevision()
            && accessRevision == response.dependencyRevisions().accessRevision();
    }
}
