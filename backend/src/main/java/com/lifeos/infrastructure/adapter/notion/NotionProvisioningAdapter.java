package com.lifeos.infrastructure.adapter.notion;

import com.lifeos.application.port.DatabaseSpec;
import com.lifeos.application.port.ExpectedShape;
import com.lifeos.application.port.FormulaSpec;
import com.lifeos.application.port.NotionProvisioningPort;
import com.lifeos.application.port.PageShape;
import com.lifeos.application.port.PropertyDefinition;
import com.lifeos.application.port.RecordSpec;
import com.lifeos.application.port.RelationSpec;
import com.lifeos.application.port.RollupSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.application.port.VerificationResult;
import com.lifeos.domain.workspace.ProvisionedResourceType;
import com.lifeos.infrastructure.adapter.notion.dto.NotionBlock;
import com.lifeos.infrastructure.adapter.notion.dto.NotionBlockChildrenResponse;
import com.lifeos.infrastructure.adapter.notion.dto.NotionDataSourceResponse;
import com.lifeos.infrastructure.adapter.notion.dto.NotionDataSourceSummary;
import com.lifeos.infrastructure.adapter.notion.dto.NotionDatabaseResponse;
import com.lifeos.infrastructure.adapter.notion.dto.NotionPageResponse;
import com.lifeos.infrastructure.adapter.notion.dto.NotionRichText;
import com.lifeos.infrastructure.adapter.notion.dto.NotionSearchResponse;
import com.lifeos.infrastructure.adapter.notion.dto.NotionTitleProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@EnableConfigurationProperties(NotionProperties.class)
public class NotionProvisioningAdapter implements NotionProvisioningPort {

    private static final int MAX_SEARCH_PAGES = 50; // ADR-0012 [ASSUMPTION], see architecture §5.2

    private final NotionProperties properties;
    private final NotionClient client;

    public NotionProvisioningAdapter(NotionProperties properties, RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.properties = properties;
        this.client = new NotionClient(properties, restClientBuilder, objectMapper);
    }

    private static String requirePrimaryDataSourceId(NotionDatabaseResponse db, String ctx) {
        List<NotionDataSourceSummary> ds = db.dataSources();
        if (ds == null || ds.isEmpty() || ds.get(0) == null || ds.get(0).id() == null) {
            throw new NotionApiException("Notion API error: no data source on " + ctx);
        }
        return ds.get(0).id();
    }

    private static NotionDataSourceResponse requireDataSource(NotionDataSourceResponse ds, String ctx) {
        if (ds == null || ds.properties() == null) {
            throw new NotionApiException("Notion API error: data source unavailable for " + ctx);
        }
        return ds;
    }

    private static <T> List<T> nullSafe(List<T> results) {
        return results == null ? List.of() : results;
    }

    @Override
    public String createRootPage(PageShape expected) {
        Map<String, Object> body = Map.of(
                "parent", Map.of("page_id", properties.rootParentPageId()),
                "properties", Map.of("title", titlePropertyBody(expected.title())));
        NotionPageResponse response = client.post("/pages", body, NotionPageResponse.class);
        return response.id();
    }

    @Override
    public VerificationResult verifyPage(String pageId, PageShape expected) {
        NotionPageResponse response = client.get("/pages/{id}", NotionPageResponse.class, pageId);
        if (response == null || response.archived() || response.inTrash()) {
            return VerificationResult.ABSENT;
        }
        if (!properties.rootParentPageId().equals(parentPageId(response))) {
            return VerificationResult.PRESENT_DRIFTED;
        }
        if (!titleOf(response).equals(expected.title())) {
            return VerificationResult.PRESENT_DRIFTED;
        }
        return VerificationResult.PRESENT_MATCHING;
    }

    @Override
    public void repairPage(String pageId, PageShape expected) {
        NotionPageResponse current = client.get("/pages/{id}", NotionPageResponse.class, pageId);
        if (current == null) {
            throw new NotionApiException("Notion API error: page not found during repair (id=" + pageId + ")");
        }

        boolean titleDrifted = !titleOf(current).equals(expected.title());
        boolean trashed = current.archived() || current.inTrash();
        if (titleDrifted || trashed) {
            Map<String, Object> body = Map.of(
                    "properties", Map.of("title", titlePropertyBody(expected.title())),
                    "archived", false,
                    "in_trash", false);
            client.patch("/pages/{id}", body, NotionPageResponse.class, pageId);
        }

        if (!properties.rootParentPageId().equals(parentPageId(current))) {
            Map<String, Object> body = Map.of("parent", Map.of("page_id", properties.rootParentPageId()));
            client.post("/pages/{id}/move", body, NotionPageResponse.class, pageId);
        }
    }

    @Override
    public Optional<String> findRootByIdentity(PageShape expected) {
        List<NotionPageResponse> matches = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            if (pages >= MAX_SEARCH_PAGES) {
                throw new NotionApiException("Notion search exceeded the page cap (" + MAX_SEARCH_PAGES
                        + ") while locating Dashboard '" + expected.title() + "'");
            }
            Map<String, Object> body = new HashMap<>();
            body.put("query", expected.title());
            body.put("filter", Map.of("value", "page", "property", "object"));
            if (cursor != null) {
                body.put("start_cursor", cursor);
            }
            NotionSearchResponse response = client.post("/search", body, NotionSearchResponse.class);
            pages++;
            nullSafe(response.results()).stream()
                    .filter(result -> !result.archived() && !result.inTrash())
                    .filter(result -> properties.rootParentPageId().equals(parentPageId(result)))
                    .filter(result -> titleOf(result).equals(expected.title()))
                    .forEach(matches::add);
            cursor = response.hasMore() ? response.nextCursor() : null;
        } while (cursor != null);

        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            throw new NotionApiException("Ambiguous Dashboard identity: " + matches.size()
                    + " pages titled '" + expected.title() + "' found under the configured root parent");
        }
        return Optional.of(matches.get(0).id());
    }

    // requires Notion-Version >= 2025-09-03 (data-source model, ADR-0005)
    @Override
    public String createDatabase(String parentPageId, DatabaseSpec spec) {
        Map<String, Object> propertyConfigs = new LinkedHashMap<>();
        for (PropertyDefinition property : spec.properties()) {
            propertyConfigs.put(property.name(), propertyConfig(property));
        }
        Map<String, Object> body = Map.of(
                "parent", Map.of("type", "page_id", "page_id", parentPageId),
                "title", List.of(Map.of("text", Map.of("content", spec.title()))),
                "initial_data_source", Map.of("properties", propertyConfigs));
        NotionDatabaseResponse response = client.post("/databases", body, NotionDatabaseResponse.class);
        return response.id();
    }

    // requires Notion-Version >= 2025-09-03 (data-source model, ADR-0005)
    @Override
    public VerificationResult verify(String databaseId, ProvisionedResourceType type, ExpectedShape expected) {
        NotionDatabaseResponse response = client.get("/databases/{id}", NotionDatabaseResponse.class, databaseId);
        if (response == null || response.archived() || response.inTrash()) {
            return VerificationResult.ABSENT;
        }
        if (!titleOf(response).equals(expected.title())) {
            return VerificationResult.PRESENT_DRIFTED;
        }
        String dsId = requirePrimaryDataSourceId(response, expected.title());
        NotionDataSourceResponse dataSource = requireDataSource(
                client.get("/data_sources/{id}", NotionDataSourceResponse.class, dsId), expected.title());
        for (PropertyDefinition required : expected.requiredProperties()) {
            if (!dataSource.properties().containsKey(required.name())) {
                return VerificationResult.PRESENT_DRIFTED;
            }
        }
        return VerificationResult.PRESENT_MATCHING;
    }

    // requires Notion-Version >= 2025-09-03 (data-source model, ADR-0005)
    @Override
    public Optional<String> findChildByIdentity(String parentPageId, ProvisionedResourceType type, ExpectedShape expected) {
        List<NotionBlock> matches = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            if (pages >= MAX_SEARCH_PAGES) {
                throw new NotionApiException("Notion search exceeded the page cap (" + MAX_SEARCH_PAGES
                        + ") while locating " + expected.title() + " under the Dashboard");
            }
            NotionBlockChildrenResponse response = cursor == null
                    ? client.get("/blocks/{id}/children", NotionBlockChildrenResponse.class, parentPageId)
                    : client.get("/blocks/{id}/children?start_cursor={cursor}", NotionBlockChildrenResponse.class, parentPageId, cursor);
            pages++;
            nullSafe(response.results()).stream()
                    .filter(block -> "child_database".equals(block.type()))
                    .filter(block -> block.childDatabase() != null && expected.title().equals(block.childDatabase().title()))
                    .forEach(matches::add);
            cursor = response.hasMore() ? response.nextCursor() : null;
        } while (cursor != null);

        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            throw new NotionApiException("Ambiguous " + expected.title() + " database identity: " + matches.size()
                    + " child databases titled '" + expected.title() + "' found under the Dashboard");
        }
        return Optional.of(matches.get(0).id());
    }

    // requires Notion-Version >= 2025-09-03 (data-source model, ADR-0005)
    @Override
    public void repairShape(String databaseId, ExpectedShape expected) {
        NotionDatabaseResponse current = client.get("/databases/{id}", NotionDatabaseResponse.class, databaseId);
        if (current == null) {
            throw new NotionApiException("Notion API error: database not found during repair (id=" + databaseId + ")");
        }
        if (!titleOf(current).equals(expected.title())) {
            Map<String, Object> body = Map.of("title", List.of(Map.of("text", Map.of("content", expected.title()))));
            client.patch("/databases/{id}", body, NotionDatabaseResponse.class, databaseId);
        }

        String dsId = requirePrimaryDataSourceId(current, expected.title());
        NotionDataSourceResponse dataSource = requireDataSource(
                client.get("/data_sources/{id}", NotionDataSourceResponse.class, dsId), expected.title());
        Map<String, Object> missing = new LinkedHashMap<>();
        for (PropertyDefinition required : expected.requiredProperties()) {
            if (!dataSource.properties().containsKey(required.name())) {
                missing.put(required.name(), propertyConfig(required));
            }
        }
        if (!missing.isEmpty()) {
            Map<String, Object> body = Map.of("properties", missing);
            client.patch("/data_sources/{id}", body, NotionDataSourceResponse.class, dsId);
        }
    }

    @Override
    public void ensureRelation(RelationSpec spec) {
        throw new UnsupportedOperationException("Notion relation creation not yet implemented: requires the Notion API client");
    }

    @Override
    public void ensureRollup(RollupSpec spec) {
        throw new UnsupportedOperationException("Notion rollup creation not yet implemented: requires the Notion API client");
    }

    @Override
    public void ensureFormula(FormulaSpec spec) {
        throw new UnsupportedOperationException("Notion formula creation not yet implemented: requires the Notion API client");
    }

    @Override
    public boolean hasSampleRecords(String databaseId) {
        throw new UnsupportedOperationException("Notion sample record lookup not yet implemented: requires the Notion API client");
    }

    @Override
    public void insertSampleRecords(String databaseId, List<RecordSpec> records) {
        throw new UnsupportedOperationException("Notion sample record insertion not yet implemented: requires the Notion API client");
    }

    private static Map<String, Object> titlePropertyBody(String title) {
        return Map.of("title", List.of(Map.of("text", Map.of("content", title))));
    }

    private static String parentPageId(NotionPageResponse response) {
        return response.parent() == null ? null : response.parent().pageId();
    }

    private static String titleOf(NotionPageResponse response) {
        if (response.properties() == null) {
            return "";
        }
        NotionTitleProperty titleProperty = response.properties().get("title");
        if (titleProperty == null || titleProperty.title() == null) {
            return "";
        }
        return titleProperty.title().stream().map(NotionRichText::plainText).collect(Collectors.joining());
    }

    private static String titleOf(NotionDatabaseResponse response) {
        if (response.title() == null) {
            return "";
        }
        return response.title().stream().map(NotionRichText::plainText).collect(Collectors.joining());
    }

    private static Map<String, Object> propertyConfig(PropertyDefinition definition) {
        return switch (definition.type()) {
            case TITLE -> Map.of("type", "title", "title", Map.of());
            case RICH_TEXT -> Map.of("type", "rich_text", "rich_text", Map.of());
            case DATE -> Map.of("type", "date", "date", Map.of());
            case URL -> Map.of("type", "url", "url", Map.of());
            case EMAIL -> Map.of("type", "email", "email", Map.of());
            case SELECT -> Map.of("type", "select", "select",
                    Map.of("options", definition.options().stream().map(name -> Map.of("name", name)).toList()));
        };
    }
}
