package com.lifeos.application.usecase.habit;

import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.DatabaseSpec;
import com.lifeos.application.port.ExpectedShape;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.port.NotionPropertyType;
import com.lifeos.application.port.PropertyDefinition;
import com.lifeos.application.port.VerificationResult;
import com.lifeos.application.usecase.workspace.WorkspaceLedgerWriter;
import com.lifeos.domain.workspace.Workspace;
import com.lifeos.domain.workspace.WorkspaceRepository;
import com.lifeos.infrastructure.adapter.notion.NotionApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.lifeos.domain.workspace.ProvisionedResourceType.DASHBOARD;
import static com.lifeos.domain.workspace.ProvisionedResourceType.HABITS_DB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateHabitsDatabaseServiceTest {

    @Mock private NotionProvisioningPort notion;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkspaceLedgerWriter ledger;

    private CreateHabitsDatabaseService service;

    @BeforeEach
    void setUp() {
        service = new CreateHabitsDatabaseService(notion, workspaceRepository, ledger);
    }

    private static Workspace workspaceWithDashboard(UUID id) {
        Workspace base = Workspace.reconstitute(id, UUID.randomUUID(), "Personal", List.of());
        return base.record(DASHBOARD, "dash-id");
    }

    private static Workspace workspaceWithDashboardAndHabitsLedger(UUID id, String habitsNotionId) {
        return workspaceWithDashboard(id).record(HABITS_DB, habitsNotionId);
    }

    @Test
    void execute_throwsWhenWorkspaceNotFound() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Workspace not found: " + id);

        verifyNoInteractions(notion);
        verifyNoInteractions(ledger);
    }

    @Test
    void execute_throwsWhenNoDashboardLedgerEntry() {
        UUID id = UUID.randomUUID();
        Workspace workspace = Workspace.reconstitute(id, UUID.randomUUID(), "Personal", List.of());
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspace));

        assertThatThrownBy(() -> service.execute(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No confirmed Dashboard for workspace " + id);

        verifyNoInteractions(notion);
        verifyNoInteractions(ledger);
    }

    @Test
    void execute_createsWhenColdAndNoOrphan() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboard(id)));
        when(notion.findChildByIdentity(eq("dash-id"), eq(HABITS_DB), any())).thenReturn(Optional.empty());
        when(notion.createDatabase(eq("dash-id"), any())).thenReturn("new-db-id");

        ProvisioningStepResult result = service.execute(id);

        ArgumentCaptor<DatabaseSpec> specCaptor = ArgumentCaptor.forClass(DatabaseSpec.class);
        verify(notion).createDatabase(eq("dash-id"), specCaptor.capture());
        DatabaseSpec spec = specCaptor.getValue();
        assertThat(spec.properties()).hasSize(2);
        assertThat(spec.properties().stream().map(PropertyDefinition::name)).containsExactly("Name", "Frequency");
        PropertyDefinition frequency = spec.properties().stream().filter(p -> p.name().equals("Frequency")).findFirst().orElseThrow();
        assertThat(frequency.options()).containsExactly("DAILY", "WEEKLY", "MONTHLY");
        verify(ledger).record(id, HABITS_DB, "new-db-id");
        assertThat(result).isEqualTo(new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.CREATED, null));
    }

    @Test
    void execute_adoptsWhenColdAndOrphanMatches() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboard(id)));
        when(notion.findChildByIdentity(eq("dash-id"), eq(HABITS_DB), any())).thenReturn(Optional.of("orphan-id"));
        when(notion.verify(eq("orphan-id"), eq(HABITS_DB), any())).thenReturn(VerificationResult.PRESENT_MATCHING);

        ProvisioningStepResult result = service.execute(id);

        verify(notion, never()).createDatabase(any(), any());
        verify(notion, never()).repairShape(any(), any());
        verify(ledger).record(id, HABITS_DB, "orphan-id");
        assertThat(result).isEqualTo(new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.RECONCILED, null));
    }

    @Test
    void execute_adoptsAndRepairsWhenColdAndOrphanDrifted() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboard(id)));
        when(notion.findChildByIdentity(eq("dash-id"), eq(HABITS_DB), any())).thenReturn(Optional.of("orphan-id"));
        when(notion.verify(eq("orphan-id"), eq(HABITS_DB), any())).thenReturn(VerificationResult.PRESENT_DRIFTED);

        ProvisioningStepResult result = service.execute(id);

        verify(notion).repairShape(eq("orphan-id"), any());
        verify(ledger).record(id, HABITS_DB, "orphan-id");
        assertThat(result.outcome()).isEqualTo(ProvisioningOutcome.REPAIRED);
    }

    @Test
    void execute_propagatesAmbiguousMatchFailureOnColdPath() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboard(id)));
        NotionApiException ambiguous = new NotionApiException("Ambiguous Habits database identity: 2 child databases titled 'Habits' found under the Dashboard");
        when(notion.findChildByIdentity(eq("dash-id"), eq(HABITS_DB), any())).thenThrow(ambiguous);

        assertThatThrownBy(() -> service.execute(id)).isSameAs(ambiguous);

        verifyNoInteractions(ledger);
    }

    @Test
    void execute_reconcilesWhenWarmAndMatching() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboardAndHabitsLedger(id, "existing-id")));
        when(notion.verify(eq("existing-id"), eq(HABITS_DB), any())).thenReturn(VerificationResult.PRESENT_MATCHING);

        ProvisioningStepResult result = service.execute(id);

        verify(notion, never()).createDatabase(any(), any());
        verify(notion, never()).repairShape(any(), any());
        verify(notion, never()).findChildByIdentity(any(), any(), any());
        verifyNoInteractions(ledger);
        assertThat(result).isEqualTo(new ProvisioningStepResult(HABITS_DB, ProvisioningOutcome.RECONCILED, null));
    }

    @Test
    void execute_repairsWhenWarmAndDrifted() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboardAndHabitsLedger(id, "existing-id")));
        when(notion.verify(eq("existing-id"), eq(HABITS_DB), any())).thenReturn(VerificationResult.PRESENT_DRIFTED);

        ProvisioningStepResult result = service.execute(id);

        verify(notion).repairShape(eq("existing-id"), any());
        verify(ledger).record(id, HABITS_DB, "existing-id");
        assertThat(result.outcome()).isEqualTo(ProvisioningOutcome.REPAIRED);
    }

    @Test
    void execute_reAdoptsWhenWarmAndDeletedAndOrphanFound() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboardAndHabitsLedger(id, "existing-id")));
        when(notion.verify(eq("existing-id"), eq(HABITS_DB), any())).thenReturn(VerificationResult.ABSENT);
        when(notion.findChildByIdentity(eq("dash-id"), eq(HABITS_DB), any())).thenReturn(Optional.of("orphan-id"));

        ProvisioningStepResult result = service.execute(id);

        verify(notion, never()).createDatabase(any(), any());
        verify(ledger).record(id, HABITS_DB, "orphan-id");
        assertThat(result.outcome()).isEqualTo(ProvisioningOutcome.REPAIRED);
    }

    @Test
    void execute_reCreatesWhenWarmAndDeletedAndNoOrphanFound() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboardAndHabitsLedger(id, "existing-id")));
        when(notion.verify(eq("existing-id"), eq(HABITS_DB), any())).thenReturn(VerificationResult.ABSENT);
        when(notion.findChildByIdentity(eq("dash-id"), eq(HABITS_DB), any())).thenReturn(Optional.empty());
        when(notion.createDatabase(eq("dash-id"), any())).thenReturn("recreated-id");

        ProvisioningStepResult result = service.execute(id);

        verify(notion).createDatabase(eq("dash-id"), any());
        verify(ledger).record(id, HABITS_DB, "recreated-id");
        assertThat(result.outcome()).isEqualTo(ProvisioningOutcome.REPAIRED);
    }

    @Test
    void execute_propagatesAmbiguousMatchFailureOnWarmAbsentPath() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboardAndHabitsLedger(id, "existing-id")));
        when(notion.verify(eq("existing-id"), eq(HABITS_DB), any())).thenReturn(VerificationResult.ABSENT);
        NotionApiException ambiguous = new NotionApiException("Ambiguous Habits database identity: 2 child databases titled 'Habits' found under the Dashboard");
        when(notion.findChildByIdentity(eq("dash-id"), eq(HABITS_DB), any())).thenThrow(ambiguous);

        assertThatThrownBy(() -> service.execute(id)).isSameAs(ambiguous);

        verifyNoInteractions(ledger);
    }

    @Test
    void execute_propagatesNotionFailureFromVerifyWithoutWritingLedger() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboardAndHabitsLedger(id, "existing-id")));
        NotionApiException failure = new NotionApiException("Notion API error (status=500, code=internal_server_error): boom");
        when(notion.verify(eq("existing-id"), eq(HABITS_DB), any())).thenThrow(failure);

        assertThatThrownBy(() -> service.execute(id)).isSameAs(failure);

        verifyNoInteractions(ledger);
    }

    @Test
    void execute_propagatesNotionFailureFromCreateWithoutWritingLedger() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboard(id)));
        when(notion.findChildByIdentity(eq("dash-id"), eq(HABITS_DB), any())).thenReturn(Optional.empty());
        NotionApiException failure = new NotionApiException("Notion API error (status=500, code=internal_server_error): boom");
        when(notion.createDatabase(eq("dash-id"), any())).thenThrow(failure);

        assertThatThrownBy(() -> service.execute(id)).isSameAs(failure);

        verifyNoInteractions(ledger);
    }

    @Test
    void execute_neverInvokesRelationRollupFormulaOrSampleOrPageMethods() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboard(id)));
        when(notion.findChildByIdentity(eq("dash-id"), eq(HABITS_DB), any())).thenReturn(Optional.empty());
        when(notion.createDatabase(eq("dash-id"), any())).thenReturn("new-db-id");

        service.execute(id);

        verify(notion, never()).ensureRelation(any());
        verify(notion, never()).ensureRollup(any());
        verify(notion, never()).ensureFormula(any());
        verify(notion, never()).hasSampleRecords(any());
        verify(notion, never()).insertSampleRecords(any(), any());
        verify(notion, never()).createRootPage(any());
        verify(notion, never()).verifyPage(any(), any());
        verify(notion, never()).repairPage(any(), any());
        verify(notion, never()).findRootByIdentity(any());
    }

    @Test
    void execute_isNotAnnotatedTransactional() throws NoSuchMethodException {
        assertThat(CreateHabitsDatabaseService.class.getMethod("execute", UUID.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(CreateHabitsDatabaseService.class.isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    void habitsSpec_buildsTwoPropertiesWithFrequencyOptionsFromEnum() {
        DatabaseSpec spec = CreateHabitsDatabaseService.habitsSpec();

        assertThat(spec.title()).isEqualTo("Habits");
        assertThat(spec.properties()).hasSize(2);
        assertThat(spec.properties().get(0)).isEqualTo(PropertyDefinition.of("Name", NotionPropertyType.TITLE));
        assertThat(spec.properties().get(1)).isEqualTo(new PropertyDefinition("Frequency", NotionPropertyType.SELECT,
                List.of("DAILY", "WEEKLY", "MONTHLY")));
        assertThat(spec.properties().stream().map(PropertyDefinition::name))
                .doesNotContain("Description", "Due Date");
    }

    @Test
    void habitsExpectedShape_matchesSpecProperties() {
        ExpectedShape shape = CreateHabitsDatabaseService.habitsExpectedShape();

        assertThat(shape.title()).isEqualTo("Habits");
        assertThat(shape.requiredProperties()).isEqualTo(CreateHabitsDatabaseService.habitsSpec().properties());
    }
}
