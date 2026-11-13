package dev.docquery.api.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param corsAllowedOrigins browser origins allowed to call the API cross-origin
 * @param adminRole Keycloak realm role that makes a user a platform admin (admin page, all usage,
 * dead letters, per-user limits)
 */
@ConfigurationProperties("docquery.security")
public record DocQuerySecurityProperties(List<String> corsAllowedOrigins, @DefaultValue("docquery-admin") String adminRole) {

	public DocQuerySecurityProperties {
		corsAllowedOrigins = (corsAllowedOrigins == null) ? List.of() : List.copyOf(corsAllowedOrigins);
	}

}
