package com.lifeos.infrastructure.adapter.notion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.application.port.NotionCredentials;
import com.lifeos.application.port.NotionCredentialsHolder;
import com.lifeos.application.port.PageShape;
import com.lifeos.application.port.ParentConstraint;
import com.lifeos.application.port.VerificationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NotionProvisioningAdapterTest {

    private static final String ROOT_PARENT_ID = "root-parent-id";
    private static final String TOKEN = "test-token";
    private static final PageShape SHAPE = new PageShape("LifeOS — Personal", ParentConstraint.ROOT_PARENT);

    private MockRestServiceServer server;
    private NotionProvisioningAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        NotionProperties properties = new NotionProperties("2026-03-11");
        adapter = new NotionProvisioningAdapter(properties, builder, new ObjectMapper());
        NotionCredentialsHolder.set(new NotionCredentials(TOKEN, ROOT_PARENT_ID));
    }

    @AfterEach
    void tearDown() {
        NotionCredentialsHolder.clear();
    }

    @Test
    void createRootPage_postsToPagesEndpointWithParentAndTitle_returnsId() {
        server.expect(requestTo("https://api.notion.com/v1/pages"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(header("Notion-Version", "2026-03-11"))
                .andExpect(jsonPath("$.parent.page_id").value(ROOT_PARENT_ID))
                .andExpect(jsonPath("$.properties.title.title[0].text.content").value("LifeOS — Personal"))
                .andRespond(withSuccess("{\"id\":\"new-page-id\"}", MediaType.APPLICATION_JSON));

        String id = adapter.createRootPage(SHAPE);

        assertThat(id).isEqualTo("new-page-id");
        server.verify();
    }

    @Test
    void verifyPage_returnsAbsentOn404() {
        server.expect(requestTo("https://api.notion.com/v1/pages/page-id"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"object\":\"error\",\"status\":404,\"code\":\"object_not_found\",\"message\":\"not found\"}"));

        VerificationResult result = adapter.verifyPage("page-id", SHAPE);

        assertThat(result).isEqualTo(VerificationResult.ABSENT);
        server.verify();
    }

    @Test
    void verifyPage_returnsAbsentWhenArchived() {
        server.expect(requestTo("https://api.notion.com/v1/pages/page-id"))
                .andRespond(withSuccess(pageJson(true, false, ROOT_PARENT_ID, "LifeOS — Personal"), MediaType.APPLICATION_JSON));

        VerificationResult result = adapter.verifyPage("page-id", SHAPE);

        assertThat(result).isEqualTo(VerificationResult.ABSENT);
        server.verify();
    }

    @Test
    void verifyPage_returnsAbsentWhenInTrash() {
        server.expect(requestTo("https://api.notion.com/v1/pages/page-id"))
                .andRespond(withSuccess(pageJson(false, true, ROOT_PARENT_ID, "LifeOS — Personal"), MediaType.APPLICATION_JSON));

        VerificationResult result = adapter.verifyPage("page-id", SHAPE);

        assertThat(result).isEqualTo(VerificationResult.ABSENT);
        server.verify();
    }

    @Test
    void verifyPage_returnsDriftedOnTitleMismatch() {
        server.expect(requestTo("https://api.notion.com/v1/pages/page-id"))
                .andRespond(withSuccess(pageJson(false, false, ROOT_PARENT_ID, "Something Else"), MediaType.APPLICATION_JSON));

        VerificationResult result = adapter.verifyPage("page-id", SHAPE);

        assertThat(result).isEqualTo(VerificationResult.PRESENT_DRIFTED);
        server.verify();
    }

    @Test
    void verifyPage_returnsDriftedOnParentMismatch() {
        server.expect(requestTo("https://api.notion.com/v1/pages/page-id"))
                .andRespond(withSuccess(pageJson(false, false, "other-parent-id", "LifeOS — Personal"), MediaType.APPLICATION_JSON));

        VerificationResult result = adapter.verifyPage("page-id", SHAPE);

        assertThat(result).isEqualTo(VerificationResult.PRESENT_DRIFTED);
        server.verify();
    }

    @Test
    void verifyPage_returnsMatchingWhenTitleAndParentMatch() {
        server.expect(requestTo("https://api.notion.com/v1/pages/page-id"))
                .andRespond(withSuccess(pageJson(false, false, ROOT_PARENT_ID, "LifeOS — Personal"), MediaType.APPLICATION_JSON));

        VerificationResult result = adapter.verifyPage("page-id", SHAPE);

        assertThat(result).isEqualTo(VerificationResult.PRESENT_MATCHING);
        server.verify();
    }

    @Test
    void repairPage_patchesTitleWhenDrifted() {
        server.expect(requestTo("https://api.notion.com/v1/pages/page-id"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(pageJson(false, false, ROOT_PARENT_ID, "Something Else"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/pages/page-id"))
                .andExpect(method(org.springframework.http.HttpMethod.PATCH))
                .andExpect(jsonPath("$.properties.title.title[0].text.content").value("LifeOS — Personal"))
                .andExpect(jsonPath("$.archived").value(false))
                .andExpect(jsonPath("$.in_trash").value(false))
                .andRespond(withSuccess(pageJson(false, false, ROOT_PARENT_ID, "LifeOS — Personal"), MediaType.APPLICATION_JSON));

        adapter.repairPage("page-id", SHAPE);

        server.verify();
    }

    @Test
    void repairPage_movesPageWhenParentDrifted() {
        server.expect(requestTo("https://api.notion.com/v1/pages/page-id"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(pageJson(false, false, "other-parent-id", "LifeOS — Personal"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/pages/page-id/move"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.parent.page_id").value(ROOT_PARENT_ID))
                .andRespond(withSuccess(pageJson(false, false, ROOT_PARENT_ID, "LifeOS — Personal"), MediaType.APPLICATION_JSON));

        adapter.repairPage("page-id", SHAPE);

        server.verify();
    }

    @Test
    void repairPage_patchesAndMovesWhenBothDrifted() {
        server.expect(requestTo("https://api.notion.com/v1/pages/page-id"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(pageJson(false, false, "other-parent-id", "Something Else"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/pages/page-id"))
                .andExpect(method(org.springframework.http.HttpMethod.PATCH))
                .andRespond(withSuccess(pageJson(false, false, "other-parent-id", "LifeOS — Personal"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/pages/page-id/move"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(pageJson(false, false, ROOT_PARENT_ID, "LifeOS — Personal"), MediaType.APPLICATION_JSON));

        adapter.repairPage("page-id", SHAPE);

        server.verify();
    }

    @Test
    void repairPage_restoresTrashedPage() {
        server.expect(requestTo("https://api.notion.com/v1/pages/page-id"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(pageJson(false, true, ROOT_PARENT_ID, "LifeOS — Personal"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/pages/page-id"))
                .andExpect(method(org.springframework.http.HttpMethod.PATCH))
                .andExpect(jsonPath("$.in_trash").value(false))
                .andExpect(jsonPath("$.archived").value(false))
                .andRespond(withSuccess(pageJson(false, false, ROOT_PARENT_ID, "LifeOS — Personal"), MediaType.APPLICATION_JSON));

        adapter.repairPage("page-id", SHAPE);

        server.verify();
    }

    @Test
    void findRootByIdentity_postsSearchAndReturnsSingleMatch() {
        server.expect(requestTo("https://api.notion.com/v1/search"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.query").value("LifeOS — Personal"))
                .andExpect(jsonPath("$.filter.value").value("page"))
                .andExpect(jsonPath("$.filter.property").value("object"))
                .andRespond(withSuccess(searchJson(pageJson(false, false, ROOT_PARENT_ID, "LifeOS — Personal", "found-id")), MediaType.APPLICATION_JSON));

        Optional<String> result = adapter.findRootByIdentity(SHAPE);

        assertThat(result).contains("found-id");
        server.verify();
    }

    @Test
    void findRootByIdentity_treatsNullResultsAsEmptyPage() {
        server.expect(requestTo("https://api.notion.com/v1/search"))
                .andRespond(withSuccess("{\"has_more\":false,\"next_cursor\":null}", MediaType.APPLICATION_JSON));

        Optional<String> result = adapter.findRootByIdentity(SHAPE);

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void findRootByIdentity_throwsWhenSearchExceedsPageCap() {
        for (int i = 0; i < 50; i++) {
            server.expect(requestTo("https://api.notion.com/v1/search"))
                    .andRespond(withSuccess("{\"results\":[],\"has_more\":true,\"next_cursor\":\"cursor-1\"}", MediaType.APPLICATION_JSON));
        }

        assertThatThrownBy(() -> adapter.findRootByIdentity(SHAPE))
                .isInstanceOf(NotionApiException.class)
                .hasMessageContaining("page cap");
        server.verify();
    }

    @Test
    void findRootByIdentity_returnsEmptyWhenNoHit() {
        server.expect(requestTo("https://api.notion.com/v1/search"))
                .andRespond(withSuccess("{\"results\":[],\"has_more\":false,\"next_cursor\":null}", MediaType.APPLICATION_JSON));

        Optional<String> result = adapter.findRootByIdentity(SHAPE);

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void findRootByIdentity_filtersOutResultsWithWrongParent() {
        server.expect(requestTo("https://api.notion.com/v1/search"))
                .andRespond(withSuccess(searchJson(pageJson(false, false, "other-parent-id", "LifeOS — Personal", "found-id")), MediaType.APPLICATION_JSON));

        Optional<String> result = adapter.findRootByIdentity(SHAPE);

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void findRootByIdentity_filtersOutResultsWithNonExactTitleMatch() {
        server.expect(requestTo("https://api.notion.com/v1/search"))
                .andRespond(withSuccess(searchJson(pageJson(false, false, ROOT_PARENT_ID, "LifeOS — Personal Extended", "found-id")), MediaType.APPLICATION_JSON));

        Optional<String> result = adapter.findRootByIdentity(SHAPE);

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void findRootByIdentity_filtersOutArchivedOrTrashedResults() {
        server.expect(requestTo("https://api.notion.com/v1/search"))
                .andRespond(withSuccess(searchJson(pageJson(true, false, ROOT_PARENT_ID, "LifeOS — Personal", "found-id")), MediaType.APPLICATION_JSON));

        Optional<String> result = adapter.findRootByIdentity(SHAPE);

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void findRootByIdentity_throwsOnMultipleMatches() {
        String body = "{\"results\":[" + pageJson(false, false, ROOT_PARENT_ID, "LifeOS — Personal", "id-1")
                + "," + pageJson(false, false, ROOT_PARENT_ID, "LifeOS — Personal", "id-2")
                + "],\"has_more\":false,\"next_cursor\":null}";
        server.expect(requestTo("https://api.notion.com/v1/search"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.findRootByIdentity(SHAPE))
                .isInstanceOf(NotionApiException.class)
                .hasMessageNotContaining(TOKEN);
        server.verify();
    }

    @Test
    void client_retriesOn429ThenSucceeds() {
        server.expect(requestTo("https://api.notion.com/v1/pages"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"object\":\"error\",\"status\":429,\"code\":\"rate_limited\",\"message\":\"slow down\"}"));
        server.expect(requestTo("https://api.notion.com/v1/pages"))
                .andRespond(withSuccess("{\"id\":\"new-page-id\"}", MediaType.APPLICATION_JSON));

        String id = adapter.createRootPage(SHAPE);

        assertThat(id).isEqualTo("new-page-id");
        server.verify();
    }

    @Test
    void client_failsAfterExhaustingBoundedRetries() {
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo("https://api.notion.com/v1/pages"))
                    .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                            .header("Retry-After", "0")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"object\":\"error\",\"status\":429,\"code\":\"rate_limited\",\"message\":\"slow down\"}"));
        }

        assertThatThrownBy(() -> adapter.createRootPage(SHAPE))
                .isInstanceOf(NotionApiException.class);
        server.verify();
    }

    @Test
    void client_neverLeaksTokenInExceptionMessage() {
        server.expect(requestTo("https://api.notion.com/v1/pages"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"object\":\"error\",\"status\":401,\"code\":\"unauthorized\",\"message\":\"API token is invalid.\"}"));

        assertThatThrownBy(() -> adapter.createRootPage(SHAPE))
                .isInstanceOf(NotionApiException.class)
                .hasMessageContaining("unauthorized")
                .hasMessageContaining("API token is invalid.")
                .hasMessageNotContaining(TOKEN)
                .hasMessageNotContaining("Bearer");
        server.verify();
    }

    @Test
    void client_mapsGenericErrorStatusToNotionApiExceptionWithCodeAndMessage() {
        server.expect(requestTo("https://api.notion.com/v1/pages"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"object\":\"error\",\"status\":500,\"code\":\"internal_server_error\",\"message\":\"boom\"}"));

        assertThatThrownBy(() -> adapter.createRootPage(SHAPE))
                .isInstanceOf(NotionApiException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("internal_server_error")
                .hasMessageContaining("boom");
        server.verify();
    }

    @Test
    void verifyPage_encodesPageIdAsUriVariable() {
        server.expect(requestTo("https://api.notion.com/v1/pages/a%20b"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(pageJson(false, false, ROOT_PARENT_ID, "LifeOS — Personal", "a b"), MediaType.APPLICATION_JSON));

        VerificationResult result = adapter.verifyPage("a b", SHAPE);

        assertThat(result).isEqualTo(VerificationResult.PRESENT_MATCHING);
        server.verify();
    }

    @Test
    void findRootByIdentity_followsPaginationAcrossPages() {
        server.expect(requestTo("https://api.notion.com/v1/search"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.start_cursor").doesNotExist())
                .andRespond(withSuccess("{\"results\":[],\"has_more\":true,\"next_cursor\":\"cursor-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/search"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.start_cursor").value("cursor-1"))
                .andRespond(withSuccess(searchJson(pageJson(false, false, ROOT_PARENT_ID, "LifeOS — Personal", "found-id")), MediaType.APPLICATION_JSON));

        Optional<String> result = adapter.findRootByIdentity(SHAPE);

        assertThat(result).contains("found-id");
        server.verify();
    }

    @Test
    void findRootByIdentity_throwsOnMatchesSpreadAcrossPages() {
        server.expect(requestTo("https://api.notion.com/v1/search"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("{\"results\":[" + pageJson(false, false, ROOT_PARENT_ID, "LifeOS — Personal", "id-1")
                        + "],\"has_more\":true,\"next_cursor\":\"cursor-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.notion.com/v1/search"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(searchJson(pageJson(false, false, ROOT_PARENT_ID, "LifeOS — Personal", "id-2")), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.findRootByIdentity(SHAPE))
                .isInstanceOf(NotionApiException.class)
                .hasMessageNotContaining(TOKEN);
        server.verify();
    }

    private static String pageJson(boolean archived, boolean inTrash, String parentId, String title) {
        return pageJson(archived, inTrash, parentId, title, "page-id");
    }

    private static String pageJson(boolean archived, boolean inTrash, String parentId, String title, String id) {
        return """
                {
                  "id": "%s",
                  "archived": %s,
                  "in_trash": %s,
                  "parent": { "type": "page_id", "page_id": "%s" },
                  "properties": { "title": { "title": [ { "plain_text": "%s" } ] } }
                }
                """.formatted(id, archived, inTrash, parentId, title);
    }

    private static String searchJson(String pageJson) {
        return "{\"results\":[" + pageJson + "],\"has_more\":false,\"next_cursor\":null}";
    }
}
