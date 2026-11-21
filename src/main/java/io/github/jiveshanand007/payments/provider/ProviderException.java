package io.github.jiveshanand007.payments.provider;

/** The provider could not be reached or refused the request. */
public class ProviderException extends RuntimeException {

	public ProviderException(String message) {
		super(message);
	}

	public ProviderException(String message, Throwable cause) {
		super(message, cause);
	}
}
