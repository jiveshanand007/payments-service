package io.github.jiveshanand007.payments.checkout;

import java.time.Instant;

public record Checkout(
		String id,
		String siteId,
		String reference,
		String item,
		long amountMinor,
		String currency,
		CheckoutStatus status,
		String provider,
		String providerRef,
		String providerPaymentId,
		String checkoutUrl,
		String successUrl,
		String cancelUrl,
		String idempotencyKey,
		String requestHash,
		Instant expiresAt,
		Instant createdAt,
		Instant updatedAt,
		Instant paidAt) {
}
