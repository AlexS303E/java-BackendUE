package com.game.backend.matchprofile.application;

import com.game.backend.matchprofile.repository.MatchProfileRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.cache.RedisCacheService;
import com.game.backend.common.api.ApiException;
import com.game.backend.matchprofile.api.BuildMatchProfileRequest;
import com.game.backend.matchprofile.api.MatchProfileResponse;
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

    public MatchProfileResponse findByDependencyTuple(
        BuildMatchProfileRequest request,
        long catalogVersion,
        long weaponPresetRevision,
        long outfitPresetRevision,
        long accessRevision
    ) {
        MatchProfileResponse cached = cacheService.getMatchProfile(
            request.playerId(),
            request.realmId(),
            request.classTag(),
            request.teamTag(),
            request.weaponPresetSlot(),
            request.outfitPresetSlot(),
            catalogVersion,
            weaponPresetRevision,
            outfitPresetRevision,
            accessRevision
        ).orElse(null);
        if (cached != null && matchesDependencyTuple(
            request,
            catalogVersion,
            weaponPresetRevision,
            outfitPresetRevision,
            accessRevision,
            cached
        )) {
            return cached;
        }

        List<String> payloads = repository.findFreshPayload(
            request.playerId(),
            request.realmId(),
            request.classTag(),
            request.teamTag(),
            request.weaponPresetSlot(),
            request.outfitPresetSlot(),
            catalogVersion,
            weaponPresetRevision,
            outfitPresetRevision,
            accessRevision
        );
        if (payloads.isEmpty()) {
            return null;
        }
        try {
            MatchProfileResponse response = objectMapper.readValue(payloads.getFirst(), MatchProfileResponse.class);
            if (matchesDependencyTuple(
                request,
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

    public void save(BuildMatchProfileRequest request, MatchProfileResponse response) {
        OffsetDateTime now = OffsetDateTime.now();
        String payload = toJson(response);

        repository.saveProfile(
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
        cacheService.putMatchProfile(response);
    }

    private String toJson(MatchProfileResponse response) {
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
        BuildMatchProfileRequest request,
        long catalogVersion,
        long weaponPresetRevision,
        long outfitPresetRevision,
        long accessRevision,
        MatchProfileResponse response
    ) {
        return response != null
            && response.dependencyRevisions() != null
            && request.playerId().equals(response.playerId())
            && request.realmId().equals(response.realmId())
            && request.classTag().equals(response.classTag())
            && request.teamTag().equals(response.teamTag())
            && request.weaponPresetSlot() == response.weaponPresetSlot()
            && request.outfitPresetSlot() == response.outfitPresetSlot()
            && catalogVersion == response.catalogVersion()
            && weaponPresetRevision == response.dependencyRevisions().weaponPresetRevision()
            && outfitPresetRevision == response.dependencyRevisions().outfitPresetRevision()
            && accessRevision == response.dependencyRevisions().accessRevision();
    }
}
