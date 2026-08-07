package com.lifeos.infrastructure.adapter.web;

import com.lifeos.application.port.NotionProvisioningPort;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CreateWorkspaceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class NotionPortTestConfiguration {
        @Bean
        @Primary
        NotionProvisioningPort notionProvisioningPort() {
            return Mockito.mock(NotionProvisioningPort.class);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void postWorkspace_persistsWorkspaceAndReturnsFailedReportWhileStepsAreStubbed() {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest("Personal", UUID.randomUUID(), false, "token", "root-id");

        ResponseEntity<String> response = restTemplate.postForEntity("/api/workspaces", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from workspaces where person_id = ? and name = ?",
                Integer.class, request.personId(), request.name());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void reRunWithSamePersonIdAndName_reusesExistingWorkspaceRow() {
        UUID personId = UUID.randomUUID();
        CreateWorkspaceRequest request = new CreateWorkspaceRequest("Personal", personId, false, "token", "root-id");

        restTemplate.postForEntity("/api/workspaces", request, String.class);
        restTemplate.postForEntity("/api/workspaces", request, String.class);

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from workspaces where person_id = ? and name = ?",
                Integer.class, personId, "Personal");
        assertThat(count).isEqualTo(1);
    }
}
