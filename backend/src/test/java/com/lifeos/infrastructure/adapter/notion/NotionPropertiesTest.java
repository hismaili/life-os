package com.lifeos.infrastructure.adapter.notion;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class NotionPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void toString_redactsTokenToFixedMarker() {
        String rendering = new NotionProperties("secret-token", "2026-03-11", "root-id").toString();

        assertThat(rendering).contains("****");
        assertThat(rendering).doesNotContain("secret-token");
    }

    @Test
    void toString_stillRendersVersionAndRootParentPageId() {
        String rendering = new NotionProperties("secret-token", "2026-03-11", "root-id").toString();

        assertThat(rendering).contains("2026-03-11");
        assertThat(rendering).contains("root-id");
    }

    @Test
    void contextFails_whenTokenBlank() {
        contextRunner
                .withPropertyValues(
                        "notion.token=",
                        "notion.version=2026-03-11",
                        "notion.root-parent-page-id=abc")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void contextFails_whenVersionBlank() {
        contextRunner
                .withPropertyValues(
                        "notion.token=secret",
                        "notion.version=",
                        "notion.root-parent-page-id=abc")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void contextFails_whenRootParentPageIdBlank() {
        contextRunner
                .withPropertyValues(
                        "notion.token=secret",
                        "notion.version=2026-03-11",
                        "notion.root-parent-page-id=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void contextStarts_whenAllPropertiesPresent() {
        contextRunner
                .withPropertyValues(
                        "notion.token=secret",
                        "notion.version=2026-03-11",
                        "notion.root-parent-page-id=abc")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    NotionProperties properties = context.getBean(NotionProperties.class);
                    assertThat(properties.token()).isEqualTo("secret");
                    assertThat(properties.version()).isEqualTo("2026-03-11");
                    assertThat(properties.rootParentPageId()).isEqualTo("abc");
                });
    }

    @Configuration
    @EnableConfigurationProperties(NotionProperties.class)
    static class TestConfig {
    }
}
