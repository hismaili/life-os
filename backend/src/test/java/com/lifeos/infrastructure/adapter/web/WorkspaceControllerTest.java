package com.lifeos.infrastructure.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.application.dto.workspace.ProvisioningOutcome;
import com.lifeos.application.dto.workspace.ProvisioningReport;
import com.lifeos.application.dto.workspace.ProvisioningStepResult;
import com.lifeos.application.usecase.workspace.CreateWorkspaceUseCase;
import com.lifeos.domain.workspace.ProvisionedResourceType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkspaceController.class)
class WorkspaceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CreateWorkspaceUseCase createWorkspace;

    private final UUID workspaceId = UUID.randomUUID();

    @Test
    void create_returns201WhenNewStructuresCreated() throws Exception {
        when(createWorkspace.execute(any())).thenReturn(new ProvisioningReport(workspaceId, List.of(
                new ProvisioningStepResult(ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.CREATED, null))));

        mockMvc.perform(post("/api/workspaces")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new CreateWorkspaceRequest("Personal", UUID.randomUUID(), false, "token", "root-id"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workspaceId").value(workspaceId.toString()))
                .andExpect(jsonPath("$.steps[0].type").value("DASHBOARD"));
    }

    @Test
    void create_returns200WhenPureReconcile() throws Exception {
        when(createWorkspace.execute(any())).thenReturn(new ProvisioningReport(workspaceId, List.of(
                new ProvisioningStepResult(ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.RECONCILED, null))));

        mockMvc.perform(post("/api/workspaces")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new CreateWorkspaceRequest("Personal", UUID.randomUUID(), false, "token", "root-id"))))
                .andExpect(status().isOk());
    }

    @Test
    void create_returns400ProblemDetailOnBlankName() throws Exception {
        mockMvc.perform(post("/api/workspaces")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new CreateWorkspaceRequest("", UUID.randomUUID(), false, "token", "root-id"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").exists());
    }

    @Test
    void create_returns400ProblemDetailOnMissingPersonId() throws Exception {
        mockMvc.perform(post("/api/workspaces")
                        .contentType("application/json")
                        .content("{\"name\":\"Personal\",\"sampleData\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void create_returns502ProblemDetailWhenReportFailed() throws Exception {
        when(createWorkspace.execute(any())).thenReturn(new ProvisioningReport(workspaceId, List.of(
                new ProvisioningStepResult(ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.FAILED, "boom"))));

        mockMvc.perform(post("/api/workspaces")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new CreateWorkspaceRequest("Personal", UUID.randomUUID(), false, "token", "root-id"))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.workspaceId").value(workspaceId.toString()))
                .andExpect(jsonPath("$.steps").exists());
    }

    @Test
    void create_failureResponseNeverLeaksInternalStepDetail() throws Exception {
        when(createWorkspace.execute(any())).thenReturn(new ProvisioningReport(workspaceId, List.of(
                new ProvisioningStepResult(ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.FAILED,
                        "org.postgresql.util.PSQLException: internal constraint xyz"))));

        mockMvc.perform(post("/api/workspaces")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new CreateWorkspaceRequest("Personal", UUID.randomUUID(), false, "token", "root-id"))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.steps[0].type").value("DASHBOARD"))
                .andExpect(jsonPath("$.steps[0].outcome").value("FAILED"))
                .andExpect(jsonPath("$.steps[0].detail").doesNotExist())
                .andExpect(content().string(not(containsString("PSQLException"))));
    }

    @Test
    void create_returns409ProblemDetailOnDataIntegrityViolation() throws Exception {
        when(createWorkspace.execute(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        mockMvc.perform(post("/api/workspaces")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new CreateWorkspaceRequest("Personal", UUID.randomUUID(), false, "token", "root-id"))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(content().string(not(containsString("duplicate key"))));
    }

    @Test
    void create_returns500ProblemDetailOnUnexpectedError() throws Exception {
        when(createWorkspace.execute(any())).thenThrow(new RuntimeException("secret internal detail"));

        mockMvc.perform(post("/api/workspaces")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new CreateWorkspaceRequest("Personal", UUID.randomUUID(), false, "token", "root-id"))))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(content().string(not(containsString("secret internal detail"))));
    }

    @Test
    void create_neverExposesDomainWorkspaceInResponse() throws Exception {
        when(createWorkspace.execute(any())).thenReturn(new ProvisioningReport(workspaceId, List.of(
                new ProvisioningStepResult(ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.CREATED, null))));

        mockMvc.perform(post("/api/workspaces")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new CreateWorkspaceRequest("Personal", UUID.randomUUID(), false, "token", "root-id"))))
                .andExpect(jsonPath("$.name").doesNotExist())
                .andExpect(jsonPath("$.personId").doesNotExist())
                .andExpect(jsonPath("$.resources").doesNotExist());
    }

    @Test
    void create_neverEchoesTheSuppliedNotionTokenInResponse() throws Exception {
        when(createWorkspace.execute(any())).thenReturn(new ProvisioningReport(workspaceId, List.of(
                new ProvisioningStepResult(ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.CREATED, null))));

        mockMvc.perform(post("/api/workspaces")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new CreateWorkspaceRequest("Personal", UUID.randomUUID(), false, "super-secret-token", "root-id"))))
                .andExpect(content().string(not(containsString("super-secret-token"))));
    }
}
