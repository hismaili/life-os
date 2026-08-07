package com.lifeos.application.port;

/**
 * Carries the caller-supplied Notion credentials (BYOK) for the duration of a single
 * {@code CreateWorkspaceUseCase.execute(...)} call. Set once at the top of that call and cleared
 * in a {@code finally}, so the token never outlives the request and is never held by a
 * longer-lived Spring bean.
 */
public final class NotionCredentialsHolder {

    private static final ThreadLocal<NotionCredentials> CURRENT = new ThreadLocal<>();

    private NotionCredentialsHolder() {
    }

    public static void set(NotionCredentials credentials) {
        CURRENT.set(credentials);
    }

    public static NotionCredentials require() {
        NotionCredentials credentials = CURRENT.get();
        if (credentials == null) {
            throw new IllegalStateException(
                    "No Notion credentials in scope for this call — CreateWorkspaceService must set them before invoking NotionProvisioningPort");
        }
        return credentials;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
