package dev.docquery.api.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Maps the access token to authorities: the usual {@code SCOPE_*} authorities, plus {@link #ADMIN}
 * when the token carries the configured Keycloak <em>realm</em> role ({@code realm_access.roles}).
 * <p>
 * Platform admin is the only global role and the only one kept in Keycloak: it is an operator
 * privilege (cross-tenant usage, dead letters, limits), not per-workspace business data, which stays
 * in the database ({@code WorkspaceAccess}).
 */
public final class AdminAuthorities implements Converter<Jwt, Collection<GrantedAuthority>> {

	/** Authority granted to platform admins; {@code hasRole("ADMIN")}. */
	public static final String ADMIN = "ROLE_ADMIN";

	private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

	private final String adminRole;

	public AdminAuthorities(String adminRole) {
		this.adminRole = adminRole;
	}

	@Override
	public Collection<GrantedAuthority> convert(Jwt jwt) {
		List<GrantedAuthority> authorities = new ArrayList<>(this.scopes.convert(jwt));
		if (realmRoles(jwt).contains(this.adminRole)) {
			authorities.add(new SimpleGrantedAuthority(ADMIN));
		}
		return authorities;
	}

	private static Collection<?> realmRoles(Jwt jwt) {
		Object realmAccess = jwt.getClaims().get("realm_access");
		if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
			return roles;
		}
		return List.of();
	}

	public static boolean isAdmin(Authentication authentication) {
		return authentication != null
				&& authentication.getAuthorities().stream().anyMatch(a -> ADMIN.equals(a.getAuthority()));
	}

}
