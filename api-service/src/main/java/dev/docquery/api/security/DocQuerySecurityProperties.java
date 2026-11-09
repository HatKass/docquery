package dev.docquery.api.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param corsAllowedOrigins browser origins allowed to call the API cross-origin
 */
@ConfigurationProperties("docquery.security")
public record DocQuerySecurityProperties(List<String> corsAllowedOrigins) {

	public DocQuerySecurityProperties {
		corsAllowedOrigins = (corsAllowedOrigins == null) ? List.of() : List.copyOf(corsAllowedOrigins);
	}

}
