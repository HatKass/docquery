package dev.docquery.api.common.error;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Base class for expected business errors. Spring MVC renders it as an RFC 9457 {@link ProblemDetail}
 * with a stable, machine-readable {@code type} the frontend can switch on.
 */
public class ApiException extends ErrorResponseException {

	public static final String TYPE_PREFIX = "urn:docquery:problem:";

	public ApiException(HttpStatus status, String code, String title, String detail) {
		super(status, problem(status, code, title, detail), null);
	}

	public static URI type(String code) {
		return URI.create(TYPE_PREFIX + code);
	}

	private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(type(code));
		problem.setTitle(title);
		return problem;
	}

	public static ApiException notFound(String code, String detail) {
		return new ApiException(HttpStatus.NOT_FOUND, code, "Not found", detail);
	}

	public static ApiException forbidden(String code, String detail) {
		return new ApiException(HttpStatus.FORBIDDEN, code, "Forbidden", detail);
	}

	public static ApiException conflict(String code, String detail) {
		return new ApiException(HttpStatus.CONFLICT, code, "Conflict", detail);
	}

	public static ApiException unprocessable(String code, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, "Unprocessable request", detail);
	}

}
