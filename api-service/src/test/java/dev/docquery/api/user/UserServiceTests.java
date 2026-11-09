package dev.docquery.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class UserServiceTests {

	private final UserRepository repository = mock(UserRepository.class);

	private final UserService service = new UserService(this.repository);

	@Test
	void mapsStandardOidcClaims() {
		stubUpsert();

		this.service.resolve(jwt(b -> b.claim("preferred_username", "olivia")
			.claim("email", " olivia@example.test ")
			.claim("name", "Olivia Owner")));

		verify(this.repository).upsert(eq("sub-1"), eq("olivia"), eq("olivia@example.test"), eq("Olivia Owner"),
				any(Duration.class));
	}

	@Test
	void fallsBackToGivenAndFamilyNameThenUsername() {
		stubUpsert();

		this.service.resolve(jwt(b -> b.claim("preferred_username", "max").claim("given_name", "Max")
			.claim("family_name", "Member")));
		verify(this.repository).upsert(eq("sub-1"), eq("max"), isNull(), eq("Max Member"), any(Duration.class));

		this.service.resolve(jwt(b -> b.claim("preferred_username", "vera")));
		verify(this.repository).upsert(eq("sub-1"), eq("vera"), isNull(), eq("vera"), any(Duration.class));
	}

	@Test
	void usesSubjectWhenUsernameClaimIsMissing() {
		stubUpsert();

		this.service.resolve(jwt(b -> b.claim("scope", "openid")));

		verify(this.repository).upsert(eq("sub-1"), eq("sub-1"), isNull(), eq("sub-1"), any(Duration.class));
	}

	@Test
	void rejectsTokensWithoutSubject() {
		Jwt noSubject = Jwt.withTokenValue("t").header("alg", "none").claim("preferred_username", "x").build();

		assertThatIllegalArgumentException().isThrownBy(() -> this.service.resolve(noSubject));
	}

	private void stubUpsert() {
		when(this.repository.upsert(any(), any(), any(), any(), any()))
			.thenReturn(new CurrentUser(UUID.randomUUID(), "sub-1", "u", null, "u"));
	}

	private static Jwt jwt(Consumer<Jwt.Builder> claims) {
		Jwt.Builder builder = Jwt.withTokenValue("token")
			.header("alg", "RS256")
			.subject("sub-1")
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(60));
		claims.accept(builder);
		return builder.build();
	}

	@Test
	void resolvedUserIsWhatTheRepositoryReturns() {
		CurrentUser stored = new CurrentUser(UUID.randomUUID(), "sub-1", "olivia", null, "Olivia");
		when(this.repository.upsert(any(), any(), any(), any(), any())).thenReturn(stored);

		assertThat(this.service.resolve(jwt(b -> b.claim("preferred_username", "olivia")))).isSameAs(stored);
	}

}
