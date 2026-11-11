package dev.docquery.api.common.error;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.util.unit.DataSize;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves the API as a ProblemDetail. Spring MVC already renders its own exceptions and
 * {@link ApiException}s that way; this adds field-level details for bean-validation failures.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private final DataSize maxFileSize;

	GlobalExceptionHandler(@Value("${docquery.documents.max-file-size:25MB}") DataSize maxFileSize) {
		this.maxFileSize = maxFileSize;
	}

	record FieldViolation(String field, String message) {
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ex.getBody();
		problem.setType(ApiException.type("validation"));
		problem.setTitle("Invalid request");
		problem.setDetail("One or more fields are invalid.");
		List<FieldViolation> violations = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(e -> new FieldViolation(e.getField(), e.getDefaultMessage()))
			.toList();
		problem.setProperty("errors", violations);
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	/** Upload rejected by the multipart parser before it reached a controller. */
	@Override
	protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ex.getBody();
		problem.setType(ApiException.type("file-too-large"));
		problem.setTitle("File too large");
		long max = this.maxFileSize.toMegabytes();
		problem.setDetail("Files can be at most " + max + " MB.");
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

}
