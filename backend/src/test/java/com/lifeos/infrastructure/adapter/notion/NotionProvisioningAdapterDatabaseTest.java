package com.lifeos.infrastructure.adapter.notion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.application.port.DatabaseSpec;
import com.lifeos.application.port.ExpectedShape;
import com.lifeos.application.port.NotionPropertyType;
import com.lifeos.application.port.PropertyDefinition;
import com.lifeos.application.port.VerificationResult;
import com.lifeos.domain.workspace.ProvisionedResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NotionProvisioningAdapterDatabaseTest {

    private static final String TOKEN = "test-token";
    private static final String DASHBOARD_ID = "dashboard-id";
    private static final ExpectedShape EXPECTED_SHAPE = new ExpectedShape("Projects", List.of(
            PropertyDefinition.of("Name", NotionPropertyType.TITLE),
            PropertyDefinition.of("Description", NotionPropertyType.RICH_TEXT),
            new PropertyDefinition("Status", NotionPropertyType.SELECT, List.of("Planned", "Active", "On hold", "Done")),
            PropertyDefinition.of("Due Date", NotionPropertyType.DATE)));
    private static final ExpectedShape URL_EXPECTED_SHAPE = new ExpectedShape("Resources", List.of(
            PropertyDefinition.of("Title", NotionPropertyType.TITLE),
            PropertyDefinition.of("URL", NotionPropertyType.URL)));
    private static final ExpectedShape EMAIL_EXPECTED_SHAPE = new ExpectedShape("People", List.of(
            PropertyDefinition.of("Name", NotionPropertyType.TITLE),
            PropertyDefinition.of("Email", NotionPropertyType.EMAIL)));

    private MockRestServiceServer server;
    private NotionProvisioningAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        NotionProperties properties = new NotionProperties(TOKEN, "2026-03-11", "root-parent-id");
        adapter = new NotionProvisioningAdapter(properties, builder, new ObjectMapper());
    }

    @Test
    void createDatabase_postsDatabaseWithInitialDataSource_returnsDatabaseId() {
        DatabaseSpec spec = new DatabaseSpec("Projects", EXPECTED_SHAPE.requiredProperties());

        server.expect(requestTo("https://api.notion.com/v1/databases"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.parent.page_id").value(DASHBOARD_ID))
                .andExpect(jsonPath("$.title[0].text.content").value("Projects"))
                .andExpect(jsonPath("$.initial_data_source.properties.Name.type").value("title"))
                .andExpect(jsonPath("$.initial_data_source.properties.Description.type").value("rich_text"))
                .andExpect(jsonPath("$.initial_data_source.properties.Status.type").value("select"))
                .andExpect(jsonPath("$.initial_data_source.properties.Status.select.options.length()").value(4))
                .andExpect(jsonPath("$.initial_data_source.properties.Status.select.options[0].name").value("Planned"))
                .andExpect(jsonPath("$.initial_data_source.properties.Status.select.options[1].name").value("Active"))
                .andExpect(jsonPath("$.initial_data_source.properties.Status.select.options[2].name").value("On hold"))
                .andExpect(jsonPath("$.initial_data_source.properties.Status.select.options[3].name").value("Done"))
                .andExpect(jsonPath("$.initial_data_source.properties['Due Date'].type").value("date"))
                .andRespond(withSuccess("{\"id\":\"new-db-id\",\"data_sources\":[{\"id\":\"ds-1\",\"name\":\"Projects\"}]}", MediaType.APPLICATION_JSON));

        String id = adapter.createDatabase(DASHBOARD_ID, spec);

        assertThat(id).isEqualTo("new-db-id");
        server.verify();
    }

    @Test
    void createDatabase_postsUrlPropertyWithEmptyUrlConfig() {
        DatabaseSpec spec = new DatabaseSpec("Resources", URL_EXPECTED_SHAPE.requiredProperties());

        server.expect(requestTo("https://api.notion.com/v1/databases"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.title[0].text.content").value("Resources"))
                .andExpect(jsonPath("$.initial_data_source.properties.Title.type").value("title"))
                .andExpect(jsonPath("$.initial_data_source.properties.URL.type").value("url"))
                .andExpect(jsonPath("$.initial_data_source.properties.URL.url").isEmpty())
                .andRespond(withSuccess("{\"id\":\"new-db-id\",\"data_sources\":[{\"id\":\"ds-1\",\"name\":\"Resources\"}]}", MediaType.APPLICATION_JSON));

        String id = adapter.createDatabase(DASHBOARD_ID, spec);

        assertThat(id).isEqualTo("new-db-id");
        server.verify();
    }

    @Test
    void repairShape_addsMissingUrlPropertyWithEmptyUrlConfig() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(databaseJson(false, false, "Resources"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"properties": {"Title": {"type":"title"}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.properties.URL.type").value("url"))
                .andExpect(jsonPath("$.properties.URL.url").isEmpty())
                .andExpect(jsonPath("$.properties.Title").doesNotExist())
                .andRespond(withSuccess("{\"properties\":{}}", MediaType.APPLICATION_JSON));

        adapter.repairShape("db-id", URL_EXPECTED_SHAPE);

        server.verify();
    }

    @Test
    void createDatabase_postsEmailPropertyWithEmptyEmailConfig() {
        DatabaseSpec spec = new DatabaseSpec("People", EMAIL_EXPECTED_SHAPE.requiredProperties());

        server.expect(requestTo("https://api.notion.com/v1/databases"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.title[0].text.content").value("People"))
                .andExpect(jsonPath("$.initial_data_source.properties.Name.type").value("title"))
                .andExpect(jsonPath("$.initial_data_source.properties.Email.type").value("email"))
                .andExpect(jsonPath("$.initial_data_source.properties.Email.email").isEmpty())
                .andRespond(withSuccess("{\"id\":\"new-db-id\",\"data_sources\":[{\"id\":\"ds-1\",\"name\":\"People\"}]}", MediaType.APPLICATION_JSON));

        String id = adapter.createDatabase(DASHBOARD_ID, spec);

        assertThat(id).isEqualTo("new-db-id");
        server.verify();
    }

    @Test
    void repairShape_addsMissingEmailPropertyWithEmptyEmailConfig() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(databaseJson(false, false, "People"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"properties": {"Name": {"type":"title"}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.properties.Email.type").value("email"))
                .andExpect(jsonPath("$.properties.Email.email").isEmpty())
                .andExpect(jsonPath("$.properties.Name").doesNotExist())
                .andRespond(withSuccess("{\"properties\":{}}", MediaType.APPLICATION_JSON));

        adapter.repairShape("db-id", EMAIL_EXPECTED_SHAPE);

        server.verify();
    }

    @Test
    void verify_throwsNotionApiExceptionWhenDataSourcesEmpty() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(databaseJsonWithDataSources(false, false, "Projects", "\"data_sources\": []"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.verify("db-id", ProvisionedResourceType.PROJECTS_DB, EXPECTED_SHAPE))
                .isInstanceOf(NotionApiException.class)
                .hasMessageContaining("no data source");
        server.verify();
    }

    @Test
    void verify_throwsNotionApiExceptionWhenDataSourcesAbsent() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(databaseJsonWithoutDataSources(false, false, "Projects"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.verify("db-id", ProvisionedResourceType.PROJECTS_DB, EXPECTED_SHAPE))
                .isInstanceOf(NotionApiException.class)
                .hasMessageContaining("no data source");
        server.verify();
    }

    @Test
    void verify_throwsNotionApiExceptionWhenDataSourceLookupReturns404() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(databaseJson(false, false, "Projects"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"object\":\"error\",\"status\":404,\"code\":\"object_not_found\",\"message\":\"not found\"}"));

        assertThatThrownBy(() -> adapter.verify("db-id", ProvisionedResourceType.PROJECTS_DB, EXPECTED_SHAPE))
                .isInstanceOf(NotionApiException.class)
                .hasMessageContaining("data source unavailable");
        server.verify();
    }

    @Test
    void repairShape_throwsNotionApiExceptionWhenDataSourcesEmpty() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(databaseJsonWithDataSources(false, false, "Projects", "\"data_sources\": []"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.repairShape("db-id", EXPECTED_SHAPE))
                .isInstanceOf(NotionApiException.class)
                .hasMessageContaining("no data source");
        server.verify();
    }

    @Test
    void repairShape_throwsNotionApiExceptionWhenDataSourceLookupReturns404() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(databaseJson(false, false, "Projects"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"object\":\"error\",\"status\":404,\"code\":\"object_not_found\",\"message\":\"not found\"}"));

        assertThatThrownBy(() -> adapter.repairShape("db-id", EXPECTED_SHAPE))
                .isInstanceOf(NotionApiException.class)
                .hasMessageContaining("data source unavailable");
        server.verify();
    }

    @Test
    void findChildByIdentity_treatsNullResultsAsEmptyPage() {
        server.expect(requestTo("https://api.notion.com/v1/blocks/dashboard-id/children"))
                .andRespond(withSuccess("{\"has_more\":false,\"next_cursor\":null}", MediaType.APPLICATION_JSON));

        Optional<String> result = adapter.findChildByIdentity(DASHBOARD_ID, ProvisionedResourceType.PROJECTS_DB, EXPECTED_SHAPE);

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void findChildByIdentity_throwsWhenSearchExceedsPageCap() {
        server.expect(requestTo("https://api.notion.com/v1/blocks/dashboard-id/children"))
                .andRespond(withSuccess("{\"results\":[],\"has_more\":true,\"next_cursor\":\"cursor-1\"}", MediaType.APPLICATION_JSON));
        for (int i = 0; i < 49; i++) {
            server.expect(requestTo("https://api.notion.com/v1/blocks/dashboard-id/children?start_cursor=cursor-1"))
                    .andRespond(withSuccess("{\"results\":[],\"has_more\":true,\"next_cursor\":\"cursor-1\"}", MediaType.APPLICATION_JSON));
        }

        assertThatThrownBy(() -> adapter.findChildByIdentity(DASHBOARD_ID, ProvisionedResourceType.PROJECTS_DB, EXPECTED_SHAPE))
                .isInstanceOf(NotionApiException.class)
                .hasMessageContaining("page cap");
        server.verify();
    }

    @Test
    void verify_returnsAbsentOn404() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"object\":\"error\",\"status\":404,\"code\":\"object_not_found\",\"message\":\"not found\"}"));

        VerificationResult result = adapter.verify("db-id", ProvisionedResourceType.PROJECTS_DB, EXPECTED_SHAPE);

        assertThat(result).isEqualTo(VerificationResult.ABSENT);
        server.verify();
    }

    @Test
    void verify_returnsAbsentWhenArchived() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andRespond(withSuccess(databaseJson(true, false, "Projects"), MediaType.APPLICATION_JSON));

        VerificationResult result = adapter.verify("db-id", ProvisionedResourceType.PROJECTS_DB, EXPECTED_SHAPE);

        assertThat(result).isEqualTo(VerificationResult.ABSENT);
        server.verify();
    }

    @Test
    void verify_returnsAbsentWhenInTrash() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andRespond(withSuccess(databaseJson(false, true, "Projects"), MediaType.APPLICATION_JSON));

        VerificationResult result = adapter.verify("db-id", ProvisionedResourceType.PROJECTS_DB, EXPECTED_SHAPE);

        assertThat(result).isEqualTo(VerificationResult.ABSENT);
        server.verify();
    }

    @Test
    void verify_returnsDriftedOnTitleMismatch() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andRespond(withSuccess(databaseJson(false, false, "Something Else"), MediaType.APPLICATION_JSON));

        VerificationResult result = adapter.verify("db-id", ProvisionedResourceType.PROJECTS_DB, EXPECTED_SHAPE);

        assertThat(result).isEqualTo(VerificationResult.PRESENT_DRIFTED);
        server.verify();
    }

    @Test
    void verify_returnsDriftedWhenRequiredPropertyMissing() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andRespond(withSuccess(databaseJson(false, false, "Projects"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
                .andRespond(withSuccess("""
                        {"properties": {"Name": {"type":"title"}, "Description": {"type":"rich_text"}, "Due Date": {"type":"date"}}}
                        """, MediaType.APPLICATION_JSON));

        VerificationResult result = adapter.verify("db-id", ProvisionedResourceType.PROJECTS_DB, EXPECTED_SHAPE);

        assertThat(result).isEqualTo(VerificationResult.PRESENT_DRIFTED);
        server.verify();
    }

    @Test
    void verify_returnsMatchingWhenTitleAndAllRequiredPropertiesPresent() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andRespond(withSuccess(databaseJson(false, false, "Projects"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
                .andRespond(withSuccess("""
                        {"properties": {"Name": {"type":"title"}, "Description": {"type":"rich_text"}, "Status": {"type":"select"}, "Due Date": {"type":"date"}, "Extra": {"type":"number"}}}
                        """, MediaType.APPLICATION_JSON));

        VerificationResult result = adapter.verify("db-id", ProvisionedResourceType.PROJECTS_DB, EXPECTED_SHAPE);

        assertThat(result).isEqualTo(VerificationResult.PRESENT_MATCHING);
        server.verify();
    }

    @Test
    void verify_ignoresExtraUserOptionsAndUnrelatedProperties() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andRespond(withSuccess(databaseJson(false, false, "Projects"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
                .andRespond(withSuccess("""
                        {"properties": {
                          "Name": {"type":"title"},
                          "Description": {"type":"rich_text"},
                          "Status": {"type":"select","select":{"options":[{"name":"Custom1"},{"name":"Custom2"},{"name":"Custom3"}]}},
                          "Due Date": {"type":"date"},
                          "UserAdded": {"type":"checkbox"}
                        }}
                        """, MediaType.APPLICATION_JSON));

        VerificationResult result = adapter.verify("db-id", ProvisionedResourceType.PROJECTS_DB, EXPECTED_SHAPE);

        assertThat(result).isEqualTo(VerificationResult.PRESENT_MATCHING);
        server.verify();
    }

    @Test
    void findChildByIdentity_listsChildBlocksAndFiltersByTitle_returnsSingleMatch() {
        server.expect(requestTo("https://api.notion.com/v1/blocks/dashboard-id/children"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"results": [
                          {"id":"other-id","type":"child_page","child_page":{"title":"Notes"}},
                          {"id":"match-id","type":"child_database","child_database":{"title":"Projects"}}
                        ],"has_more":false,"next_cursor":null}
                        """, MediaType.APPLICATION_JSON));

        Optional<String> result = adapter.findChildByIdentity(DASHBOARD_ID, ProvisionedResourceType.PROJECTS_DB, EXPECTED_SHAPE);

        assertThat(result).contains("match-id");
        server.verify();
    }

    @Test
    void findChildByIdentity_returnsEmptyWhenNoChildDatabaseTitledProjects() {
        server.expect(requestTo("https://api.notion.com/v1/blocks/dashboard-id/children"))
                .andRespond(withSuccess("""
                        {"results": [
                          {"id":"other-id","type":"child_database","child_database":{"title":"Tasks"}}
                        ],"has_more":false,"next_cursor":null}
                        """, MediaType.APPLICATION_JSON));

        Optional<String> result = adapter.findChildByIdentity(DASHBOARD_ID, ProvisionedResourceType.PROJECTS_DB, EXPECTED_SHAPE);

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void findChildByIdentity_throwsOnMoreThanOneMatch() {
        server.expect(requestTo("https://api.notion.com/v1/blocks/dashboard-id/children"))
                .andRespond(withSuccess("""
                        {"results": [
                          {"id":"id-1","type":"child_database","child_database":{"title":"Projects"}},
                          {"id":"id-2","type":"child_database","child_database":{"title":"Projects"}}
                        ],"has_more":false,"next_cursor":null}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.findChildByIdentity(DASHBOARD_ID, ProvisionedResourceType.PROJECTS_DB, EXPECTED_SHAPE))
                .isInstanceOf(NotionApiException.class)
                .hasMessageContaining("2")
                .hasMessageNotContaining(TOKEN);
        server.verify();
    }

    @Test
    void findChildByIdentity_ambiguityMessageNamesTheActualDatabaseNotProjects() {
        ExpectedShape peopleShape = new ExpectedShape("People", List.of(
                PropertyDefinition.of("Name", NotionPropertyType.TITLE),
                PropertyDefinition.of("Email", NotionPropertyType.EMAIL)));
        server.expect(requestTo("https://api.notion.com/v1/blocks/dashboard-id/children"))
                .andRespond(withSuccess("""
                        {"results": [
                          {"id":"id-1","type":"child_database","child_database":{"title":"People"}},
                          {"id":"id-2","type":"child_database","child_database":{"title":"People"}}
                        ],"has_more":false,"next_cursor":null}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.findChildByIdentity(DASHBOARD_ID, ProvisionedResourceType.PEOPLE_DB, peopleShape))
                .isInstanceOf(NotionApiException.class)
                .hasMessageContaining("People")
                .hasMessageNotContaining("Projects");
        server.verify();
    }

    @Test
    void findChildByIdentity_paginatesAcrossPages() {
        server.expect(requestTo("https://api.notion.com/v1/blocks/dashboard-id/children"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"results\":[],\"has_more\":true,\"next_cursor\":\"cursor-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/blocks/dashboard-id/children?start_cursor=cursor-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"results": [
                          {"id":"match-id","type":"child_database","child_database":{"title":"Projects"}}
                        ],"has_more":false,"next_cursor":null}
                        """, MediaType.APPLICATION_JSON));

        Optional<String> result = adapter.findChildByIdentity(DASHBOARD_ID, ProvisionedResourceType.PROJECTS_DB, EXPECTED_SHAPE);

        assertThat(result).contains("match-id");
        server.verify();
    }

    @Test
    void repairShape_patchesDatabaseTitleWhenDrifted() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(databaseJson(false, false, "Something Else"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.title[0].text.content").value("Projects"))
                .andRespond(withSuccess(databaseJson(false, false, "Projects"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"properties": {"Name": {"type":"title"}, "Description": {"type":"rich_text"}, "Status": {"type":"select"}, "Due Date": {"type":"date"}}}
                        """, MediaType.APPLICATION_JSON));

        adapter.repairShape("db-id", EXPECTED_SHAPE);

        server.verify();
    }

    @Test
    void repairShape_throwsNotionApiExceptionWhenDatabaseNotFound() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"object\":\"error\",\"status\":404,\"code\":\"object_not_found\",\"message\":\"not found\"}"));

        assertThatThrownBy(() -> adapter.repairShape("db-id", EXPECTED_SHAPE))
                .isInstanceOf(NotionApiException.class)
                .hasMessageContaining("database not found during repair")
                .hasMessageContaining("db-id");
        server.verify();
    }

    @Test
    void repairShape_addsMissingPropertyOnDataSource_neverSendsNull() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(databaseJson(false, false, "Projects"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"properties": {"Name": {"type":"title"}, "Description": {"type":"rich_text"}, "Status": {"type":"select"}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.properties['Due Date'].type").value("date"))
                .andExpect(jsonPath("$.properties.Name").doesNotExist())
                .andExpect(jsonPath("$.properties.Description").doesNotExist())
                .andExpect(jsonPath("$.properties.Status").doesNotExist())
                .andRespond(withSuccess("{\"properties\":{}}", MediaType.APPLICATION_JSON));

        adapter.repairShape("db-id", EXPECTED_SHAPE);

        server.verify();
    }

    @Test
    void repairShape_batchesMultipleMissingPropertiesInOnePatch() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(databaseJson(false, false, "Projects"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"properties": {"Name": {"type":"title"}, "Due Date": {"type":"date"}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.properties.Description.type").value("rich_text"))
                .andExpect(jsonPath("$.properties.Status.type").value("select"))
                .andRespond(withSuccess("{\"properties\":{}}", MediaType.APPLICATION_JSON));

        adapter.repairShape("db-id", EXPECTED_SHAPE);

        server.verify();
    }

    @Test
    void repairShape_reAddedStatusCarriesEnumSeededOptions() {
        server.expect(requestTo("https://api.notion.com/v1/databases/db-id"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(databaseJson(false, false, "Projects"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"properties": {"Name": {"type":"title"}, "Description": {"type":"rich_text"}, "Due Date": {"type":"date"}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/data_sources/ds-1"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.properties.Status.select.options.length()").value(4))
                .andExpect(jsonPath("$.properties.Status.select.options[0].name").value("Planned"))
                .andExpect(jsonPath("$.properties.Status.select.options[1].name").value("Active"))
                .andExpect(jsonPath("$.properties.Status.select.options[2].name").value("On hold"))
                .andExpect(jsonPath("$.properties.Status.select.options[3].name").value("Done"))
                .andRespond(withSuccess("{\"properties\":{}}", MediaType.APPLICATION_JSON));

        adapter.repairShape("db-id", EXPECTED_SHAPE);

        server.verify();
    }

    @Test
    void client_neverLeaksTokenInDatabaseSliceExceptionMessage() {
        DatabaseSpec spec = new DatabaseSpec("Projects", EXPECTED_SHAPE.requiredProperties());
        server.expect(requestTo("https://api.notion.com/v1/databases"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"object\":\"error\",\"status\":401,\"code\":\"unauthorized\",\"message\":\"API token is invalid.\"}"));

        assertThatThrownBy(() -> adapter.createDatabase(DASHBOARD_ID, spec))
                .isInstanceOf(NotionApiException.class)
                .hasMessageNotContaining(TOKEN)
                .hasMessageNotContaining("Bearer");
        server.verify();
    }

    private static String databaseJson(boolean archived, boolean inTrash, String title) {
        return databaseJsonWithDataSources(archived, inTrash, title, "\"data_sources\": [ { \"id\": \"ds-1\", \"name\": \"Projects\" } ]");
    }

    private static String databaseJsonWithDataSources(boolean archived, boolean inTrash, String title, String dataSourcesField) {
        return """
                {
                  "id": "db-id",
                  "archived": %s,
                  "in_trash": %s,
                  "title": [ { "plain_text": "%s" } ],
                  %s
                }
                """.formatted(archived, inTrash, title, dataSourcesField);
    }

    private static String databaseJsonWithoutDataSources(boolean archived, boolean inTrash, String title) {
        return """
                {
                  "id": "db-id",
                  "archived": %s,
                  "in_trash": %s,
                  "title": [ { "plain_text": "%s" } ]
                }
                """.formatted(archived, inTrash, title);
    }
}
