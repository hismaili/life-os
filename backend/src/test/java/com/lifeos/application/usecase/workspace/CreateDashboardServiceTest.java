package com.lifeos.application.usecase.workspace;

import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.port.PageShape;
import com.lifeos.application.port.ParentConstraint;
import com.lifeos.application.port.VerificationResult;
import com.lifeos.domain.workspace.ProvisionedResourceType;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateDashboardServiceTest {

    private static final String EXPECTED_TITLE = "LifeOS — Personal";

    @Mock private NotionProvisioningPort notion;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkspaceLedgerWriter ledger;

    private CreateDashboardService service;

    @BeforeEach
    void setUp() {
        service = new CreateDashboardService(notion, workspaceRepository, ledger);
    }

    private static Workspace freshWorkspace(UUID id) {
        return Workspace.reconstitute(id, UUID.randomUUID(), "Personal", List.of());
    }

    private static Workspace workspaceWithDashboardLedger(UUID id, String notionId) {
        Workspace base = Workspace.reconstitute(id, UUID.randomUUID(), "Personal", List.of());
        return base.record(DASHBOARD, notionId);
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
    void execute_createsWhenNoLedgerAndNoOrphan() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(freshWorkspace(id)));
        when(notion.findRootByIdentity(any())).thenReturn(Optional.empty());
        when(notion.createRootPage(any())).thenReturn("new-id");

        ProvisioningStepResult result = service.execute(id);

        ArgumentCaptor<PageShape> shapeCaptor = ArgumentCaptor.forClass(PageShape.class);
        verify(notion).createRootPage(shapeCaptor.capture());
        assertThat(shapeCaptor.getValue().title()).isEqualTo(EXPECTED_TITLE);
        assertThat(shapeCaptor.getValue().parent()).isEqualTo(ParentConstraint.ROOT_PARENT);
        verify(ledger).record(id, DASHBOARD, "new-id");
        assertThat(result).isEqualTo(new ProvisioningStepResult(DASHBOARD, ProvisioningOutcome.CREATED, null));
    }

    @Test
    void execute_reconcilesWhenLedgerPresentAndMatching() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboardLedger(id, "existing-id")));
        when(notion.verifyPage(eq("existing-id"), any())).thenReturn(VerificationResult.PRESENT_MATCHING);

        ProvisioningStepResult result = service.execute(id);

        verify(notion, never()).createRootPage(any());
        verify(notion, never()).repairPage(anyString(), any());
        verify(notion, never()).findRootByIdentity(any());
        verifyNoInteractions(ledger);
        assertThat(result).isEqualTo(new ProvisioningStepResult(DASHBOARD, ProvisioningOutcome.RECONCILED, null));
    }

    @Test
    void execute_repairsWhenLedgerPresentAndDrifted() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboardLedger(id, "existing-id")));
        when(notion.verifyPage(eq("existing-id"), any())).thenReturn(VerificationResult.PRESENT_DRIFTED);

        ProvisioningStepResult result = service.execute(id);

        verify(notion).repairPage(eq("existing-id"), any());
        verify(notion, never()).createRootPage(any());
        verify(notion, never()).findRootByIdentity(any());
        verify(ledger).record(id, DASHBOARD, "existing-id");
        assertThat(result.type()).isEqualTo(DASHBOARD);
        assertThat(result.outcome()).isEqualTo(ProvisioningOutcome.REPAIRED);
        assertThat(result.detail()).isNotBlank();
    }

    @Test
    void execute_reCreatesWhenLedgerPresentButPageDeletedAndNoOrphanFound() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboardLedger(id, "existing-id")));
        when(notion.verifyPage(eq("existing-id"), any())).thenReturn(VerificationResult.ABSENT);
        when(notion.findRootByIdentity(any())).thenReturn(Optional.empty());
        when(notion.createRootPage(any())).thenReturn("recreated-id");

        ProvisioningStepResult result = service.execute(id);

        verify(notion).createRootPage(any());
        verify(ledger).record(id, DASHBOARD, "recreated-id");
        assertThat(result.outcome()).isEqualTo(ProvisioningOutcome.REPAIRED);
        assertThat(result.detail()).isNotBlank();
    }

    @Test
    void execute_readoptsWhenLedgerPresentButPageDeletedAndOrphanFound() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboardLedger(id, "existing-id")));
        when(notion.verifyPage(eq("existing-id"), any())).thenReturn(VerificationResult.ABSENT);
        when(notion.findRootByIdentity(any())).thenReturn(Optional.of("orphan-id"));

        ProvisioningStepResult result = service.execute(id);

        verify(notion, never()).createRootPage(any());
        verify(ledger).record(id, DASHBOARD, "orphan-id");
        assertThat(result.outcome()).isEqualTo(ProvisioningOutcome.REPAIRED);
        assertThat(result.detail()).isNotBlank();
    }

    @Test
    void execute_adoptsOrphanWhenNoLedgerAndMatching() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(freshWorkspace(id)));
        when(notion.findRootByIdentity(any())).thenReturn(Optional.of("orphan-id"));

        ProvisioningStepResult result = service.execute(id);

        verify(notion, never()).createRootPage(any());
        verify(notion, never()).repairPage(anyString(), any());
        verify(notion, never()).verifyPage(anyString(), any());
        verify(ledger).record(id, DASHBOARD, "orphan-id");
        assertThat(result).isEqualTo(new ProvisioningStepResult(DASHBOARD, ProvisioningOutcome.RECONCILED, null));
    }

    @Test
    void execute_propagatesAmbiguousMatchFailureOnColdPath() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(freshWorkspace(id)));
        NotionApiException ambiguous = new NotionApiException("Ambiguous Dashboard identity: 2 pages titled '" + EXPECTED_TITLE + "' found under the configured root parent");
        when(notion.findRootByIdentity(any())).thenThrow(ambiguous);

        assertThatThrownBy(() -> service.execute(id)).isSameAs(ambiguous);

        verifyNoInteractions(ledger);
    }

    @Test
    void execute_propagatesAmbiguousMatchFailureOnWarmAbsentPath() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboardLedger(id, "existing-id")));
        when(notion.verifyPage(eq("existing-id"), any())).thenReturn(VerificationResult.ABSENT);
        NotionApiException ambiguous = new NotionApiException("Ambiguous Dashboard identity: 2 pages titled '" + EXPECTED_TITLE + "' found under the configured root parent");
        when(notion.findRootByIdentity(any())).thenThrow(ambiguous);

        assertThatThrownBy(() -> service.execute(id)).isSameAs(ambiguous);

        verifyNoInteractions(ledger);
    }

    @Test
    void execute_propagatesNotionFailureFromVerifyWithoutWritingLedger() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboardLedger(id, "existing-id")));
        NotionApiException failure = new NotionApiException("Notion API error (status=500, code=internal_server_error): boom");
        when(notion.verifyPage(eq("existing-id"), any())).thenThrow(failure);

        assertThatThrownBy(() -> service.execute(id)).isSameAs(failure);

        verifyNoInteractions(ledger);
    }

    @Test
    void execute_propagatesNotionFailureFromCreateWithoutWritingLedger() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(freshWorkspace(id)));
        when(notion.findRootByIdentity(any())).thenReturn(Optional.empty());
        NotionApiException failure = new NotionApiException("Notion API error (status=500, code=internal_server_error): boom");
        when(notion.createRootPage(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.execute(id)).isSameAs(failure);

        verifyNoInteractions(ledger);
    }

    @Test
    void execute_neverInvokesDatabaseOrRelationPortMethods() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(freshWorkspace(id)));
        when(notion.findRootByIdentity(any())).thenReturn(Optional.empty());
        when(notion.createRootPage(any())).thenReturn("new-id");

        service.execute(id);

        verify(notion, never()).createDatabase(any(), any());
        verify(notion, never()).ensureRelation(any());
        verify(notion, never()).ensureRollup(any());
        verify(notion, never()).ensureFormula(any());
        verify(notion, never()).hasSampleRecords(any());
        verify(notion, never()).insertSampleRecords(any(), any());
        verify(notion, never()).verify(any(), any(), any());
        verify(notion, never()).findChildByIdentity(any(), any(), any());
        verify(notion, never()).repairShape(any(), any());
    }

    @Test
    void execute_usesSameTitleAcrossAllPortCallsInOneRun() {
        UUID id = UUID.randomUUID();
        when(workspaceRepository.findById(id)).thenReturn(Optional.of(workspaceWithDashboardLedger(id, "existing-id")));
        when(notion.verifyPage(eq("existing-id"), any())).thenReturn(VerificationResult.ABSENT);
        when(notion.findRootByIdentity(any())).thenReturn(Optional.empty());
        when(notion.createRootPage(any())).thenReturn("recreated-id");

        service.execute(id);

        ArgumentCaptor<PageShape> verifyCaptor = ArgumentCaptor.forClass(PageShape.class);
        ArgumentCaptor<PageShape> findCaptor = ArgumentCaptor.forClass(PageShape.class);
        ArgumentCaptor<PageShape> createCaptor = ArgumentCaptor.forClass(PageShape.class);
        verify(notion).verifyPage(eq("existing-id"), verifyCaptor.capture());
        verify(notion).findRootByIdentity(findCaptor.capture());
        verify(notion).createRootPage(createCaptor.capture());

        assertThat(verifyCaptor.getValue().title())
                .isEqualTo(findCaptor.getValue().title())
                .isEqualTo(createCaptor.getValue().title())
                .isEqualTo(EXPECTED_TITLE);
    }

    @Test
    void execute_isNotAnnotatedTransactional() throws NoSuchMethodException {
        assertThat(CreateDashboardService.class.getMethod("execute", UUID.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(CreateDashboardService.class.isAnnotationPresent(Transactional.class)).isFalse();
    }
}
