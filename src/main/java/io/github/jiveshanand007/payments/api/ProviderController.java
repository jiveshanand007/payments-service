package io.github.jiveshanand007.payments.api;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.jiveshanand007.payments.checkout.Checkout;
import io.github.jiveshanand007.payments.checkout.CheckoutRepository;
import io.github.jiveshanand007.payments.checkout.CheckoutStatus;
import io.github.jiveshanand007.payments.events.ProviderEventService;
import io.github.jiveshanand007.payments.provider.PaymentProvider;
import io.github.jiveshanand007.payments.provider.PaymentProvider.ReturnResult;
import io.github.jiveshanand007.payments.provider.ProviderEvent;
import io.github.jiveshanand007.payments.provider.ProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** The two ways the provider talks back: server-to-server webhooks and the customer's redirect. */
@RestController
public class ProviderController {

	private static final Logger log = LoggerFactory.getLogger(ProviderController.class);

	private final PaymentProvider provider;
	private final ProviderEventService events;
	private final CheckoutRepository checkouts;

	public ProviderController(PaymentProvider provider, ProviderEventService events, CheckoutRepository checkouts) {
		this.provider = provider;
		this.events = events;
		this.checkouts = checkouts;
	}

	/** The body is read raw: the signature covers the exact bytes the provider sent. */
	@PostMapping("/v1/webhooks/{providerName}")
	ResponseEntity<Void> webhook(@PathVariable String providerName, @RequestHeader HttpHeaders headers,
			@RequestBody String rawBody) {
		if (!provider.name().equals(providerName)) {
			return ResponseEntity.notFound().build();
		}
		Map<String, String> lower = lowercase(headers);
		if (!provider.verifyWebhook(lower, rawBody)) {
			log.warn("Rejected a {} webhook with an invalid signature", providerName);
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		List<ProviderEvent> parsed;
		try {
			parsed = provider.parseWebhook(lower, rawBody);
		}
		catch (ProviderException e) {
			return ResponseEntity.badRequest().build();
		}
		events.apply(provider.name(), parsed);
		return ResponseEntity.ok().build();
	}

	/**
	 * Where the hosted page sends the customer. A valid signed "paid" result marks the checkout
	 * paid (the webhook usually arrives too; whichever is first wins), then the customer goes back
	 * to the website's success or cancel page with {@code checkout_id} attached.
	 */
	@GetMapping("/v1/return/{checkoutId}")
	ResponseEntity<String> customerReturn(@PathVariable String checkoutId, @RequestParam Map<String, String> query) {
		Checkout checkout = checkouts.find(checkoutId).orElse(null);
		if (checkout == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Unknown checkout");
		}
		ReturnResult result = provider.verifyReturn(query).orElse(null);
		if (result != null && !result.providerRef().equals(checkout.providerRef())) {
			result = null;
		}
		// Already paid through the webhook counts too, even if the redirect lost its parameters.
		boolean paid = checkout.status() == CheckoutStatus.PAID;
		if (result != null && result.paid()) {
			events.applyReturn(provider.name(), result.providerRef(), result.providerPaymentId());
			paid = true;
		}
		String target = paid ? checkout.successUrl() : checkout.cancelUrl();
		URI location = UriComponentsBuilder.fromUriString(target).queryParam("checkout_id", checkout.id()).build().toUri();
		return ResponseEntity.status(HttpStatus.SEE_OTHER).location(location).build();
	}

	private static Map<String, String> lowercase(HttpHeaders headers) {
		Map<String, String> out = new HashMap<>();
		headers.forEach((name, values) -> {
			if (!values.isEmpty()) {
				out.put(name.toLowerCase(Locale.ROOT), values.get(0));
			}
		});
		return out;
	}
}
