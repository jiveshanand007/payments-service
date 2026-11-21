package io.github.jiveshanand007.payments.checkout;

import java.time.Instant;

/** What websites see of a checkout, in API responses and webhook payloads. */
public record CheckoutView(
		String id,
		String reference,
		String item,
		long amountMinor,
		String currency,
		String status,
		String checkoutUrl,
		Instant expiresAt,
		Instant createdAt,
		Instant paidAt) {

	public static CheckoutView of(Checkout c) {
		return new CheckoutView(c.id(), c.reference(), c.item(), c.amountMinor(), c.currency(), c.status().wire(),
				c.checkoutUrl(), c.expiresAt(), c.createdAt(), c.paidAt());
	}
}
