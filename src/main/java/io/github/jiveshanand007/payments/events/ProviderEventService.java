package io.github.jiveshanand007.payments.events;

import java.util.List;
import java.util.Optional;

import io.github.jiveshanand007.payments.checkout.Checkout;
import io.github.jiveshanand007.payments.checkout.CheckoutRepository;
import io.github.jiveshanand007.payments.checkout.CheckoutService;
import io.github.jiveshanand007.payments.checkout.CheckoutStatus;
import io.github.jiveshanand007.payments.provider.ProviderEvent;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies provider webhooks exactly once. Providers deliver at least once and may retry an event
 * minutes later, so each event id is recorded in the same transaction as its effect.
 */
@Service
public class ProviderEventService {

	private final JdbcClient jdbc;
	private final CheckoutRepository checkouts;
	private final CheckoutService checkoutService;

	public ProviderEventService(JdbcClient jdbc, CheckoutRepository checkouts, CheckoutService checkoutService) {
		this.jdbc = jdbc;
		this.checkouts = checkouts;
		this.checkoutService = checkoutService;
	}

	/** @return how many events changed a checkout (duplicates and unknown checkouts count as 0) */
	@Transactional
	public int apply(String provider, List<ProviderEvent> events) {
		int changed = 0;
		for (ProviderEvent event : events) {
			Optional<Checkout> checkout = event.providerRef() != null
					? checkouts.lockByProviderRef(provider, event.providerRef())
					: checkouts.lockByProviderPaymentId(provider, event.providerPaymentId());

			int recorded = jdbc.sql("""
					INSERT INTO provider_events (provider, event_id, event_type, checkout_id)
					VALUES (:provider, :eventId, :eventType, :checkoutId)
					ON CONFLICT DO NOTHING""")
				.param("provider", provider)
				.param("eventId", event.eventId())
				.param("eventType", event.eventType())
				.param("checkoutId", checkout.map(Checkout::id).orElse(null))
				.update();
			if (recorded == 0 || checkout.isEmpty()) {
				continue; // already applied, or not a checkout of this service
			}
			if (checkoutService.moveTo(checkout.get(), event.target(), event.providerPaymentId())) {
				changed++;
			}
		}
		return changed;
	}

	/** The customer's signed redirect back from the hosted page reports a payment too. */
	@Transactional
	public boolean applyReturn(String provider, String providerRef, String providerPaymentId) {
		return checkouts.lockByProviderRef(provider, providerRef)
			.map(c -> checkoutService.moveTo(c, CheckoutStatus.PAID, providerPaymentId))
			.orElse(false);
	}
}
