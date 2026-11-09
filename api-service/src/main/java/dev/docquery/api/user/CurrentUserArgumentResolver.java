package dev.docquery.api.user;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets controllers declare a {@link CurrentUser} parameter. Resolution (and just-in-time provisioning)
 * happens only for handlers that ask for it.
 */
@Configuration(proxyBeanMethods = false)
class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

	private final UserService userService;

	CurrentUserArgumentResolver(UserService userService) {
		this.userService = userService;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(this);
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return CurrentUser.class.equals(parameter.getParameterType());
	}

	@Override
	public CurrentUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken token) {
			Jwt jwt = token.getToken();
			return this.userService.resolve(jwt);
		}
		// Unreachable for /api/** (the filter chain requires a JWT), but fail closed regardless.
		throw new IllegalStateException("No JWT authentication present for a CurrentUser parameter");
	}

}
