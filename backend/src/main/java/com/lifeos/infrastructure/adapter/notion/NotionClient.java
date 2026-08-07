package com.lifeos.infrastructure.adapter.notion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.application.port.NotionCredentialsHolder;
import com.lifeos.infrastructure.adapter.notion.dto.NotionErrorResponse;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;

class NotionClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_BACKOFF_SECONDS = 30L;
    private static final int NOTION_OVERLOADED = 529; // non-standard Notion "overloaded" status
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String notionVersion;

    NotionClient(NotionProperties properties, RestClient.Builder builder, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.notionVersion = properties.version();
        this.restClient = builder
                .baseUrl("https://api.notion.com/v1")
                .defaultStatusHandler(HttpStatusCode::isError, this::handleError)
                .build();
    }

    // Read per-call from NotionCredentialsHolder (BYOK) rather than baked into the RestClient at
    // construction time, so the token is never held by this long-lived Spring singleton.
    private void authHeaders(HttpHeaders headers) {
        headers.setBearerAuth(NotionCredentialsHolder.require().token());
        headers.set("Notion-Version", notionVersion);
    }

    static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        return ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout));
    }

    <T> T post(String path, Object body, Class<T> responseType, Object... uriVariables) {
        return executeWithRetry(() -> restClient.post().uri(path, uriVariables)
                .headers(this::authHeaders)
                .body(body).retrieve().body(responseType));
    }

    <T> T get(String path, Class<T> responseType, Object... uriVariables) {
        return executeWithRetry(() -> restClient.get().uri(path, uriVariables)
                .headers(this::authHeaders)
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                        return null;
                    }
                    if (response.getStatusCode().isError()) {
                        handleError(request, response);
                    }
                    return response.bodyTo(responseType);
                }));
    }

    <T> T patch(String path, Object body, Class<T> responseType, Object... uriVariables) {
        return executeWithRetry(() -> restClient.patch().uri(path, uriVariables)
                .headers(this::authHeaders)
                .body(body).retrieve().body(responseType));
    }

    private <T> T executeWithRetry(Supplier<T> call) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return call.get();
            } catch (RetryableStatusException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new NotionApiException(
                            "Notion API error: retries exhausted after " + MAX_ATTEMPTS
                                    + " attempts (status=" + e.status + ")");
                }
                sleep(e.retryAfterSeconds);
            }
        }
    }

    private void handleError(HttpRequest request, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status == HttpStatus.TOO_MANY_REQUESTS.value() || status == NOTION_OVERLOADED) {
            long retryAfterSeconds = parseRetryAfter(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            throw new RetryableStatusException(status, retryAfterSeconds);
        }
        NotionErrorResponse error = readError(response);
        throw new NotionApiException(
                "Notion API error (status=%d, code=%s): %s".formatted(status, error.code(), error.message()));
    }

    private NotionErrorResponse readError(ClientHttpResponse response) {
        try {
            return objectMapper.readValue(response.getBody(), NotionErrorResponse.class);
        } catch (IOException e) {
            return new NotionErrorResponse("error", 0, "unknown", "Notion API error with an unparseable body");
        }
    }

    static long parseRetryAfter(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return 1L;
        }
        try {
            long parsed = Long.parseLong(headerValue.trim());
            if (parsed < 1L) {
                return 1L;
            }
            return Math.min(parsed, MAX_BACKOFF_SECONDS);
        } catch (NumberFormatException e) {
            return 1L;
        }
    }

    private static void sleep(long seconds) {
        try {
            Thread.sleep(Duration.ofSeconds(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotionApiException("Notion API retry interrupted", e);
        }
    }

    private static final class RetryableStatusException extends RuntimeException {
        private final int status;
        private final long retryAfterSeconds;

        private RetryableStatusException(int status, long retryAfterSeconds) {
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }
}
