package dev.docquery.api.security;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless OAuth2 resource server. Keycloak authenticates users (OIDC); this service only validates
 * the access token (signature, expiry, issuer and audience) on every request. Authorization inside a
 * workspace is decided from the database, see {@code WorkspaceAccess}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DocQuerySecurityProperties.class)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, JsonMapper jsonMapper) throws Exception {
		ProblemDetailSecurityHandlers problemHandlers = new ProblemDetailSecurityHandlers(jsonMapper);
		http.authorizeHttpRequests(auth -> auth
			.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/error")
			.permitAll()
			.anyRequest()
			.authenticated())
			// Bearer tokens are never attached automatically by browsers, so CSRF does not apply.
			.csrf(AbstractHttpConfigurer::disable)
			.cors(Customizer.withDefaults())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
				.authenticationEntryPoint(problemHandlers)
				.accessDeniedHandler(problemHandlers))
			.exceptionHandling(ex -> ex.authenticationEntryPoint(problemHandlers).accessDeniedHandler(problemHandlers));
		return http.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(DocQuerySecurityProperties properties) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(properties.corsAllowedOrigins());
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
		cors.setMaxAge(Duration.ofHours(1));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", cors);
		return source;
	}

}
