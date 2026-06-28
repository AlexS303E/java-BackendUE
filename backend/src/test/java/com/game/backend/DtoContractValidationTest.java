package com.game.backend;

import com.game.backend.admin.api.AdminItemAccessUpdateRequest;
import com.game.backend.admin.api.AdminItemOperationRequest;
import com.game.backend.admin.api.AdminControlReasonRequest;
import com.game.backend.admin.api.AdminWeaponAccessControlRequest;
import com.game.backend.catalog.api.CatalogPublishRequest;
import com.game.backend.catalog.api.CatalogRollbackRequest;
import com.game.backend.matchprofile.api.BuildMatchProfileRequest;
import com.game.backend.postmatch.api.PostMatchPendingChangeResolutionRequest;
import com.game.backend.presets.api.SaveModuleRequest;
import com.game.backend.presets.api.SaveWeaponSlotRequest;
import com.game.backend.presets.api.WeaponPresetSaveRequest;
import com.game.backend.runtimechanges.api.RuntimePresetChangePayload;
import com.game.backend.runtimechanges.api.RuntimePresetChangeRequest;
import com.game.backend.runtimechanges.api.RuntimePresetChangeStep;
import com.game.backend.runtimeevents.api.RuntimeEventRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DtoContractValidationTest {
    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void shouldRejectDuplicateSupportedCatalogVersions() {
        BuildMatchProfileRequest request = new BuildMatchProfileRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "global",
                "class.assault",
                "team.red",
                1,
                1,
                List.of(1L, 1L),
                1L,
                "dev-build",
                "tdm"
        );

        assertThat(messagesFor(request))
                .contains("supported_catalog_versions must not contain duplicates");
    }

    @Test
    void shouldRejectInvalidRuntimePresetChangeContract() {
        RuntimePresetChangeRequest request = new RuntimePresetChangeRequest(
                UUID.randomUUID(),
                1L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "class.assault",
                1,
                0L,
                new RuntimePresetChangePayload(
                        2,
                        List.of(new RuntimePresetChangeStep("invalid", "primary", null, null, null))
                )
        );

        assertThat(propertyPathsFor(request))
                .contains(
                        "baseWeaponPresetRevision",
                        "runtimeChangePayload.schemaVersion",
                        "runtimeChangePayload.changes[0].op"
                );
    }

    @Test
    void shouldRejectInvalidRuntimeEventContract() {
        RuntimeEventRequest request = new RuntimeEventRequest(
                UUID.randomUUID(),
                0L,
                UUID.randomUUID(),
                "unsupported",
                UUID.randomUUID(),
                2,
                OffsetDateTime.now(),
                Map.of()
        );

        assertThat(propertyPathsFor(request))
                .contains("eventSeq", "eventType", "payloadSchemaVersion", "payload");
    }

    @Test
    void shouldRejectInvalidPostMatchAndCatalogContracts() {
        assertThat(propertyPathsFor(new PostMatchPendingChangeResolutionRequest("unknown")))
                .contains("resolution");
        assertThat(propertyPathsFor(new CatalogPublishRequest("global", 0L, 101, true, "reason")))
                .contains("catalogVersion", "rolloutPercent");
        assertThat(propertyPathsFor(new CatalogRollbackRequest("global", 0L, "reason")))
                .contains("targetCatalogVersion");
    }

    @Test
    void shouldRejectInvalidAdminCatalogVersionContracts() {
        assertThat(propertyPathsFor(new AdminItemOperationRequest(
                UUID.randomUUID(), "weapon.ak12", 0L, "reason", null, null, null
        ))).contains("catalogVersion");
        assertThat(propertyPathsFor(new AdminItemAccessUpdateRequest(
                0L, false, false, false, false, null, null, null, "reason", null
        ))).contains("catalogVersion");
        assertThat(propertyPathsFor(new AdminWeaponAccessControlRequest(
                "weapon.ak12", 0L, "shop_lock", "reason", null
        ))).contains("catalogVersion");
        assertThat(propertyPathsFor(new AdminControlReasonRequest(" ", null)))
                .contains("reason");
    }

    @Test
    void shouldRejectDuplicateWeaponPresetSlotsAndMounts() {
        SaveWeaponSlotRequest firstSlot = new SaveWeaponSlotRequest(
                "primary",
                "weapon.ak12",
                List.of(
                        new SaveModuleRequest("weapon.ak12.mount.scope.01", "module.scope.red_dot_01"),
                        new SaveModuleRequest("weapon.ak12.mount.scope.01", "module.scope.red_dot_02")
                )
        );
        WeaponPresetSaveRequest request = new WeaponPresetSaveRequest(
                1L,
                List.of(firstSlot, new SaveWeaponSlotRequest("primary", null, List.of()))
        );

        assertThat(messagesFor(request))
                .contains("slots must not contain duplicate weapon_slot_id")
                .contains("modules must not contain duplicate mount_id");
    }

    @Test
    void shouldAcceptValidDtoContracts() {
        BuildMatchProfileRequest matchProfileRequest = new BuildMatchProfileRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "global",
                "class.assault",
                "team.red",
                1,
                1,
                List.of(1L, 2L),
                1L,
                "dev-build",
                "tdm"
        );
        WeaponPresetSaveRequest presetRequest = new WeaponPresetSaveRequest(
                1L,
                List.of(new SaveWeaponSlotRequest(
                        "primary",
                        "weapon.ak12",
                        List.of(new SaveModuleRequest("weapon.ak12.mount.scope.01", "module.scope.red_dot_01"))
                ))
        );

        assertThat(VALIDATOR.validate(matchProfileRequest)).isEmpty();
        assertThat(VALIDATOR.validate(presetRequest)).isEmpty();
    }

    private Set<String> messagesFor(Object request) {
        return VALIDATOR.validate(request).stream()
                .map(ConstraintViolation::getMessage)
                .collect(java.util.stream.Collectors.toSet());
    }

    private Set<String> propertyPathsFor(Object request) {
        return VALIDATOR.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }
}
