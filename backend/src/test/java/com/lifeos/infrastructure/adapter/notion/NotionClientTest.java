package com.lifeos.infrastructure.adapter.notion;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotionClientTest {

    @Test
    void requestFactory_enforcesReadTimeoutRatherThanHangingForever() throws Exception {
        try (ServerSocket blackHole = new ServerSocket(0)) {
            Thread accepter = new Thread(() -> {
                try {
                    blackHole.accept();
                    Thread.sleep(Duration.ofSeconds(60));
                } catch (Exception ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            accepter.setDaemon(true);
            accepter.start();

            RestClient client = RestClient.builder()
                    .requestFactory(NotionClient.requestFactory(Duration.ofMillis(300), Duration.ofMillis(300)))
                    .baseUrl("http://127.0.0.1:" + blackHole.getLocalPort())
                    .build();

            long startNanos = System.nanoTime();
            assertThatThrownBy(() -> client.get().uri("/hang").retrieve().body(String.class))
                    .isInstanceOf(ResourceAccessException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - startNanos)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void parseRetryAfter_clampsLargeValuesToMax() {
        assertThat(NotionClient.parseRetryAfter("86400")).isEqualTo(30L);
    }

    @Test
    void parseRetryAfter_keepsSmallValues() {
        assertThat(NotionClient.parseRetryAfter("2")).isEqualTo(2L);
    }

    @Test
    void parseRetryAfter_fallsBackToOneOnMissingNonNumericOrNonPositive() {
        assertThat(NotionClient.parseRetryAfter(null)).isEqualTo(1L);
        assertThat(NotionClient.parseRetryAfter("")).isEqualTo(1L);
        assertThat(NotionClient.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isEqualTo(1L);
        assertThat(NotionClient.parseRetryAfter("-5")).isEqualTo(1L);
    }
}
