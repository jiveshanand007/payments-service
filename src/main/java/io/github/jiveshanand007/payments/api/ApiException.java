package io.github.jiveshanand007.payments.api;

import org.springframework.http.HttpStatus;

/** An error with a stable machine-readable code, returned to the caller as an RFC 9457 problem. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public ApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}
}
