package io.github.jiveshanand007.payments.api;

import io.github.jiveshanand007.payments.checkout.CheckoutRepository;
import io.github.jiveshanand007.payments.checkout.CheckoutService;
import io.github.jiveshanand007.payments.checkout.CheckoutService.Created;
import io.github.jiveshanand007.payments.checkout.CheckoutView;
import io.github.jiveshanand007.payments.checkout.CreateCheckoutRequest;
import io.github.jiveshanand007.payments.config.PaymentsProperties.Site;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/checkouts")
public class CheckoutController {

	private final CheckoutService service;
	private final CheckoutRepository checkouts;

	public CheckoutController(CheckoutService service, CheckoutRepository checkouts) {
		this.service = service;
		this.checkouts = checkouts;
	}

	/** 201 with the hosted checkout URL; 200 with the original checkout when an Idempotency-Key is replayed. */
	@PostMapping
	ResponseEntity<CheckoutView> create(@RequestAttribute(ApiKeyFilter.SITE_ATTRIBUTE) Site site,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody CreateCheckoutRequest request) {
		if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 255)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_idempotency_key",
					"Idempotency-Key must be 1 to 255 characters");
		}
		Created created = service.create(site, request, idempotencyKey);
		return ResponseEntity.status(created.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
			.body(CheckoutView.of(created.checkout()));
	}

	@GetMapping("/{id}")
	CheckoutView get(@RequestAttribute(ApiKeyFilter.SITE_ATTRIBUTE) Site site, @PathVariable String id) {
		return checkouts.findForSite(site.id(), id)
			.map(CheckoutView::of)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "checkout_not_found", "No checkout " + id));
	}
}
