package dev.docquery.api.security;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import dev.docquery.api.common.error.ApiException;

/**
 * Makes 401/403 responses from the security filter chain look like every other API error
 * (ProblemDetail JSON), while keeping the standard {@code WWW-Authenticate: Bearer ...} header.
 */
final class ProblemDetailSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();

	private final BearerTokenAccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();

	private final JsonMapper jsonMapper;

	ProblemDetailSecurityHandlers(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		this.bearerEntryPoint.commence(request, response, authException);
		write(request, response, HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthorized",
				"A valid bearer token is required. Sign in and try again.");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		this.bearerAccessDenied.handle(request, response, accessDeniedException);
		write(request, response, HttpStatus.FORBIDDEN, "access-denied", "Forbidden",
				"You do not have access to this resource.");
	}

	private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
			String title, String detail) throws IOException {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(ApiException.type(code));
		problem.setTitle(title);
		problem.setInstance(URI.create(request.getRequestURI()));
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		this.jsonMapper.writeValue(response.getOutputStream(), problem);
	}

}
