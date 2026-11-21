package io.github.jiveshanand007.payments.provider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A payment provider with a hosted checkout page (Razorpay today; PayPal or Stripe are further
 * implementations). The rest of the service never sees provider-specific types.
 */
public interface PaymentProvider {

	/** Short lowercase name stored with each checkout, e.g. "razorpay". */
	String name();

	/** Creates the hosted page the customer is sent to. */
	ProviderCheckout createCheckout(CheckoutSpec spec);

	/** Whether a webhook really came from the provider, checked against the raw request body. */
	boolean verifyWebhook(Map<String, String> headers, String rawBody);

	/** The status changes a verified webhook announces; events this service doesn't act on are skipped. */
	List<ProviderEvent> parseWebhook(Map<String, String> headers, String rawBody);

	/**
	 * The outcome carried by the customer's redirect back from the hosted page, if its signature
	 * is valid. Empty when the parameters were tampered with.
	 */
	Optional<ReturnResult> verifyReturn(Map<String, String> query);

	record CheckoutSpec(
			String checkoutId,
			String item,
			long amountMinor,
			String currency,
			String returnUrl,
			Instant expiresAt) {
	}

	record ProviderCheckout(String providerRef, String url) {
	}

	record ReturnResult(String providerRef, boolean paid, String providerPaymentId) {
	}
}
