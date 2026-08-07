package com.lifeos.infrastructure.adapter.notion;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class NotionClientConfiguration {

    @Bean
    RestClientCustomizer notionRestClientTimeoutCustomizer() {
        return builder -> builder.requestFactory(
                NotionClient.requestFactory(NotionClient.CONNECT_TIMEOUT, NotionClient.READ_TIMEOUT));
    }
}
