package dev.docquery.api.user;

import java.util.UUID;

/**
 * The authenticated caller, resolved from the access token and mapped to the local {@code app_user}
 * row. Declare it as a controller method parameter to receive it (see {@link CurrentUserArgumentResolver}).
 *
 * @param id local user id (used for every authorization check)
 * @param subject OIDC {@code sub} claim
 */
public record CurrentUser(UUID id, String subject, String username, String email, String displayName) {
}
