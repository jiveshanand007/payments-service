package io.github.jiveshanand007.payments.api;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Every error is a problem+json body with a {@code code} field websites can switch on. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handle(ApiException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
		problem.setProperty("code", e.code());
		return ResponseEntity.status(e.status()).body(problem);
	}
}
