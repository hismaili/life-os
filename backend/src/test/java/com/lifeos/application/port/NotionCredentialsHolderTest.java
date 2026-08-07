package com.lifeos.application.port;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotionCredentialsHolderTest {

    @AfterEach
    void clearHolder() {
        NotionCredentialsHolder.clear();
    }

    @Test
    void require_throwsWhenNothingSet() {
        assertThatThrownBy(NotionCredentialsHolder::require)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void require_returnsWhatWasSet() {
        NotionCredentials credentials = new NotionCredentials("secret-token", "root-id");

        NotionCredentialsHolder.set(credentials);

        assertThat(NotionCredentialsHolder.require()).isSameAs(credentials);
    }

    @Test
    void clear_removesSetCredentials() {
        NotionCredentialsHolder.set(new NotionCredentials("secret-token", "root-id"));

        NotionCredentialsHolder.clear();

        assertThatThrownBy(NotionCredentialsHolder::require)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void set_isIsolatedPerThread() throws InterruptedException {
        NotionCredentialsHolder.set(new NotionCredentials("main-thread-token", "root-id"));

        Throwable[] otherThreadFailure = new Throwable[1];
        Thread other = new Thread(() -> {
            try {
                assertThatThrownBy(NotionCredentialsHolder::require)
                        .isInstanceOf(IllegalStateException.class);
            } catch (Throwable t) {
                otherThreadFailure[0] = t;
            }
        });
        other.start();
        other.join();

        assertThat(otherThreadFailure[0]).isNull();
        assertThat(NotionCredentialsHolder.require().token()).isEqualTo("main-thread-token");
    }
}
