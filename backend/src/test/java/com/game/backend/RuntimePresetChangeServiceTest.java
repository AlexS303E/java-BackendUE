package com.game.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.common.api.ApiException;
import com.game.backend.outbox.application.OutboxService;
import com.game.backend.runtimechanges.api.RuntimePresetChangePayload;
import com.game.backend.runtimechanges.api.RuntimePresetChangeRequest;
import com.game.backend.runtimechanges.api.RuntimePresetChangeResponse;
import com.game.backend.runtimechanges.api.RuntimePresetChangeStep;
import com.game.backend.runtimechanges.application.RuntimeChangeConflictService;
import com.game.backend.runtimechanges.application.RuntimeOperationRecorder;
import com.game.backend.runtimechanges.application.RuntimeOperationStreamService;
import com.game.backend.runtimechanges.application.RuntimePresetChangeService;
import com.game.backend.runtimechanges.application.WeaponPresetRuntimeChangeApplier;
import com.game.backend.runtimechanges.repository.RuntimeChangesRepository;
import com.game.backend.serverauth.application.ServerAuditService;
import com.game.backend.serverauth.application.ServerIdentity;
import com.game.backend.serverauth.application.ServerMatchService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimePresetChangeServiceTest {
    private final RuntimeChangesRepository repository = mock(RuntimeChangesRepository.class);
    private final ServerMatchService serverMatchService = mock(ServerMatchService.class);
    private final ServerAuditService serverAuditService = mock(ServerAuditService.class);
    private final WeaponPresetRuntimeChangeApplier runtimeChangeApplier = mock(WeaponPresetRuntimeChangeApplier.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final RuntimeOperationRecorder operationRecorder = mock(RuntimeOperationRecorder.class);
    private final RuntimeOperationStreamService operationStreamService = mock(RuntimeOperationStreamService.class);
    private final RuntimeChangeConflictService conflictService = mock(RuntimeChangeConflictService.class);

    private final RuntimePresetChangeService service = new RuntimePresetChangeService(
        repository,
        new ObjectMapper().findAndRegisterModules(),
        serverMatchService,
        serverAuditService,
        runtimeChangeApplier,
        outboxService,
        operationRecorder,
        operationStreamService,
        conflictService
    );

    @Test
    void shouldRecordProcessingOperationBeforeApplyingRuntimeChange() {
        RuntimePresetChangeRequest request = request();
        ServerIdentity server = new ServerIdentity(UUID.randomUUID(), "global", "dev-server-build", Set.of("runtime_preset_change:write"));
        when(operationRecorder.find(request.operationId())).thenReturn(null);
        when(operationRecorder.insertProcessing(eq(request), anyString(), any(OffsetDateTime.class))).thenReturn(1);
        when(repository.lockWeaponPreset(request.playerId(), request.classTag(), request.weaponPresetSlot()))
            .thenReturn(List.of(new RuntimeChangesRepository.PresetHeader(1, request.baseWeaponPresetRevision())));
        doThrow(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "Rejected for order test"))
            .when(runtimeChangeApplier)
            .apply(
                eq(request.playerId()),
                eq(request.classTag()),
                eq(request.weaponPresetSlot()),
                anyLong(),
                eq(request.runtimeChangePayload()),
                any(OffsetDateTime.class)
            );

        RuntimePresetChangeResponse response = service.submit(server, request.operationId().toString(), request);

        assertThat(response.status()).isEqualTo("rejected");
        InOrder order = inOrder(operationRecorder, operationStreamService, repository, runtimeChangeApplier);
        order.verify(operationRecorder).find(request.operationId());
        order.verify(operationStreamService).lockAndValidateNextSequence(request);
        order.verify(operationRecorder).insertProcessing(eq(request), anyString(), any(OffsetDateTime.class));
        order.verify(repository).lockWeaponPreset(request.playerId(), request.classTag(), request.weaponPresetSlot());
        order.verify(runtimeChangeApplier).apply(
            eq(request.playerId()),
            eq(request.classTag()),
            eq(request.weaponPresetSlot()),
            anyLong(),
            eq(request.runtimeChangePayload()),
            any(OffsetDateTime.class)
        );
        verify(operationRecorder).markRejected(eq(request.operationId()), any(OffsetDateTime.class));
        verify(operationStreamService).advance(request);
    }

    private RuntimePresetChangeRequest request() {
        return new RuntimePresetChangeRequest(
            UUID.randomUUID(),
            1L,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "class.assault",
            1,
            7L,
            new RuntimePresetChangePayload(
                1,
                List.of(new RuntimePresetChangeStep("clear_weapon", "primary", null, null, null))
            )
        );
    }
}
