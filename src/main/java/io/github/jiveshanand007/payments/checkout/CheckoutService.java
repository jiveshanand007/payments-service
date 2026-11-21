package io.github.jiveshanand007.payments.checkout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import io.github.jiveshanand007.payments.api.ApiException;
import io.github.jiveshanand007.payments.config.PaymentsProperties;
import io.github.jiveshanand007.payments.config.PaymentsProperties.Site;
import io.github.jiveshanand007.payments.outbox.OutboxRepository;
import io.github.jiveshanand007.payments.provider.PaymentProvider;
import io.github.jiveshanand007.payments.provider.PaymentProvider.CheckoutSpec;
import io.github.jiveshanand007.payments.provider.PaymentProvider.ProviderCheckout;
import io.github.jiveshanand007.payments.provider.ProviderException;
import io.github.jiveshanand007.payments.site.SiteRegistry;
import io.github.jiveshanand007.payments.util.Hmac;
import io.github.jiveshanand007.payments.util.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckoutService {

	private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
	/** Razorpay rejects payment links that expire less than 15 minutes out. */
	private static final Duration MIN_EXPIRY = Duration.ofMinutes(16);

	private final CheckoutRepository checkouts;
	private final OutboxRepository outbox;
	private final PaymentProvider provider;
	private final SiteRegistry sites;
	private final ObjectMapper json;
	private final Clock clock;
	private final PaymentsProperties properties;

	public CheckoutService(CheckoutRepository checkouts, OutboxRepository outbox, PaymentProvider provider,
			SiteRegistry sites, ObjectMapper json, Clock clock, PaymentsProperties properties) {
		if (properties.checkoutExpiry().compareTo(MIN_EXPIRY) < 0) {
			throw new IllegalStateException("payments.checkout-expiry must be at least " + MIN_EXPIRY.toMinutes() + " minutes");
		}
		this.checkouts = checkouts;
		this.outbox = outbox;
		this.provider = provider;
		this.sites = sites;
		this.json = json;
		this.clock = clock;
		this.properties = properties;
	}

	public record Created(Checkout checkout, boolean replayed) {
	}

	/**
	 * Starts a checkout. With an Idempotency-Key, retrying the same request returns the original
	 * checkout instead of a second payment page; reusing the key for a different request is refused.
	 */
	public Created create(Site site, CreateCheckoutRequest req, String idempotencyKey) {
		for (String url : new String[] { req.successUrl(), req.cancelUrl() }) {
			if (!SiteRegistry.redirectAllowed(site, url)) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "redirect_not_allowed",
						"Return URLs must be on one of this site's allowed origins: " + url);
			}
		}
		String requestHash = Hmac.sha256Hex(String.join("\n", req.reference(), req.item(),
				Long.toString(req.amountMinor()), req.currencyOrDefault(), req.successUrl(), req.cancelUrl()));

		if (idempotencyKey != null) {
			var existing = checkouts.findByIdempotencyKey(site.id(), idempotencyKey);
			if (existing.isPresent()) {
				return replay(existing.get(), requestHash);
			}
		}

		String id = Ids.checkout();
		Instant now = clock.instant();
		Instant expiresAt = now.plus(properties.checkoutExpiry());
		String returnUrl = properties.publicBaseUrl().resolve("/v1/return/" + id).toString();

		ProviderCheckout hosted;
		try {
			hosted = provider.createCheckout(new CheckoutSpec(id, req.item(), req.amountMinor(), req.currencyOrDefault(),
					returnUrl, expiresAt));
		}
		catch (ProviderException e) {
			log.warn("Provider {} could not create checkout {}: {}", provider.name(), id, e.getMessage());
			throw new ApiException(HttpStatus.BAD_GATEWAY, "provider_error", e.getMessage());
		}

		Checkout checkout = new Checkout(id, site.id(), req.reference(), req.item(), req.amountMinor(),
				req.currencyOrDefault(), CheckoutStatus.CREATED, provider.name(), hosted.providerRef(), null, hosted.url(),
				req.successUrl(), req.cancelUrl(), idempotencyKey, requestHash, expiresAt, now, now, null);
		try {
			checkouts.insert(checkout);
		}
		catch (DuplicateKeyException e) {
			// Two requests with one Idempotency-Key raced; the other one won. The payment page made
			// here is never shown to anyone and simply expires.
			return replay(checkouts.findByIdempotencyKey(site.id(), idempotencyKey).orElseThrow(() -> e), requestHash);
		}
		return new Created(checkouts.find(id).orElseThrow(), false);
	}

	private Created replay(Checkout existing, String requestHash) {
		if (!requestHash.equals(existing.requestHash())) {
			throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_reused",
					"This Idempotency-Key was already used for a different checkout request");
		}
		return new Created(existing, true);
	}

	/**
	 * Moves a locked checkout to {@code target} and queues the webhook that tells the website, in
	 * the caller's transaction. Repeating a status is a no-op; a move the lifecycle forbids is ignored.
	 *
	 * @return whether the status changed
	 */
	@Transactional
	public boolean moveTo(Checkout locked, CheckoutStatus target, String providerPaymentId) {
		if (locked.status() == target) {
			return false;
		}
		if (!locked.status().canMoveTo(target)) {
			log.info("Ignoring {} -> {} for checkout {}", locked.status(), target, locked.id());
			return false;
		}
		Instant now = clock.instant();
		checkouts.updateStatus(locked.id(), target, providerPaymentId, now);

		Site site = sites.find(locked.siteId()).orElse(null);
		if (site != null && site.webhookUrl() != null) {
			Checkout updated = checkouts.find(locked.id()).orElseThrow();
			String eventId = Ids.event();
			String type = "checkout." + target.wire();
			String payload = json.writeValueAsString(Map.of(
					"id", eventId,
					"type", type,
					"created_at", now.toString(),
					"data", CheckoutView.of(updated)));
			outbox.enqueue(eventId, site.id(), locked.id(), type, payload);
		}
		return true;
	}
}
