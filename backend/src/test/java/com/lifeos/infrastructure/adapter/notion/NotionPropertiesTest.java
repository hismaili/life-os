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
    void contextFails_whenVersionBlank() {
        contextRunner
                .withPropertyValues("notion.version=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void contextStarts_whenVersionPresent() {
        contextRunner
                .withPropertyValues("notion.version=2026-03-11")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    NotionProperties properties = context.getBean(NotionProperties.class);
                    assertThat(properties.version()).isEqualTo("2026-03-11");
                });
    }

    @Configuration
    @EnableConfigurationProperties(NotionProperties.class)
    static class TestConfig {
    }
}
