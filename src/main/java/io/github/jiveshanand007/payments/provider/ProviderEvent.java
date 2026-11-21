package io.github.jiveshanand007.payments.provider;

import io.github.jiveshanand007.payments.checkout.CheckoutStatus;

/**
 * One status change announced by a provider webhook. A checkout is found by {@code providerRef}
 * (the hosted page id) when the provider sends it, otherwise by {@code providerPaymentId}
 * (refund and dispute events only name the payment).
 */
public record ProviderEvent(
		String eventId,
		String eventType,
		CheckoutStatus target,
		String providerRef,
		String providerPaymentId) {
}
