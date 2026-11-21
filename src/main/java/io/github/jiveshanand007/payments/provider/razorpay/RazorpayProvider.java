package io.github.jiveshanand007.payments.provider.razorpay;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.jiveshanand007.payments.checkout.CheckoutStatus;
import io.github.jiveshanand007.payments.config.PaymentsProperties;
import io.github.jiveshanand007.payments.provider.PaymentProvider;
import io.github.jiveshanand007.payments.provider.ProviderEvent;
import io.github.jiveshanand007.payments.provider.ProviderException;
import io.github.jiveshanand007.payments.util.Hmac;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * Razorpay through Payment Links: a page hosted by Razorpay that takes UPI, cards, netbanking
 * and wallets, so card and bank details never reach this service.
 */
@Component
public class RazorpayProvider implements PaymentProvider {

	private final PaymentsProperties.Razorpay config;
	private final HttpClient http;
	private final ObjectMapper json;

	public RazorpayProvider(PaymentsProperties properties, HttpClient http, ObjectMapper json) {
		this.config = properties.razorpay();
		this.http = http;
		this.json = json;
	}

	@Override
	public String name() {
		return "razorpay";
	}

	@Override
	public ProviderCheckout createCheckout(CheckoutSpec spec) {
		requireConfigured(config.keyId(), "RAZORPAY_KEY_ID");
		requireConfigured(config.keySecret(), "RAZORPAY_KEY_SECRET");

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("amount", spec.amountMinor());
		body.put("currency", spec.currency());
		body.put("accept_partial", false);
		body.put("description", spec.item());
		body.put("reference_id", spec.checkoutId());
		body.put("expire_by", spec.expiresAt().getEpochSecond());
		// Razorpay must not message the customer: this service holds no contact details to give it.
		body.put("notify", Map.of("sms", false, "email", false));
		body.put("reminder_enable", false);
		body.put("callback_url", spec.returnUrl());
		body.put("callback_method", "get");

		String auth = Base64.getEncoder()
			.encodeToString((config.keyId() + ":" + config.keySecret()).getBytes(StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder(config.baseUrl().resolve("/v1/payment_links"))
			.timeout(Duration.ofSeconds(15))
			.header("Authorization", "Basic " + auth)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
			.build();

		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException e) {
			throw new ProviderException("Razorpay could not be reached", e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ProviderException("Interrupted while calling Razorpay", e);
		}

		if (response.statusCode() / 100 != 2) {
			throw new ProviderException("Razorpay refused the payment link (" + response.statusCode() + "): "
					+ errorDescription(response.body()));
		}
		PaymentLink link = json.readValue(response.body(), PaymentLink.class);
		if (link.id() == null || link.shortUrl() == null) {
			throw new ProviderException("Razorpay returned a payment link without an id or URL");
		}
		return new ProviderCheckout(link.id(), link.shortUrl());
	}

	@Override
	public boolean verifyWebhook(Map<String, String> headers, String rawBody) {
		requireConfigured(config.webhookSecret(), "RAZORPAY_WEBHOOK_SECRET");
		String signature = headers.get("x-razorpay-signature");
		return signature != null && Hmac.equalHex(Hmac.sha256Hex(config.webhookSecret(), rawBody), signature);
	}

	@Override
	public List<ProviderEvent> parseWebhook(Map<String, String> headers, String rawBody) {
		Webhook webhook;
		try {
			webhook = json.readValue(rawBody, Webhook.class);
		}
		catch (JacksonException e) {
			throw new ProviderException("Unreadable Razorpay webhook", e);
		}
		// Razorpay retries a webhook with the same event id; the body hash covers senders that omit it.
		String eventId = Optional.ofNullable(headers.get("x-razorpay-event-id")).orElseGet(() -> Hmac.sha256Hex(rawBody));
		String type = webhook.event() == null ? "" : webhook.event();
		Payload p = webhook.payload() == null ? new Payload(null, null, null, null) : webhook.payload();

		List<ProviderEvent> events = new ArrayList<>();
		switch (type) {
			case "payment_link.paid" -> {
				if (p.paymentLink() != null) {
					String paymentId = p.payment() == null ? null : p.payment().entity().id();
					events.add(new ProviderEvent(eventId, type, CheckoutStatus.PAID, p.paymentLink().entity().id(), paymentId));
				}
			}
			case "payment_link.expired", "payment_link.cancelled" -> {
				if (p.paymentLink() != null) {
					events.add(new ProviderEvent(eventId, type, CheckoutStatus.EXPIRED, p.paymentLink().entity().id(), null));
				}
			}
			case "refund.processed" -> {
				// A partial refund leaves the payment paid; only a full refund changes the status.
				boolean partial = p.payment() != null && p.payment().entity().amount() != null
						&& p.payment().entity().amountRefunded() != null
						&& p.payment().entity().amountRefunded() < p.payment().entity().amount();
				if (p.refund() != null && !partial) {
					events.add(new ProviderEvent(eventId, type, CheckoutStatus.REFUNDED, null, p.refund().entity().paymentId()));
				}
			}
			case "payment.dispute.created" -> {
				if (p.dispute() != null) {
					events.add(new ProviderEvent(eventId, type, CheckoutStatus.DISPUTED, null, p.dispute().entity().paymentId()));
				}
			}
			default -> {
				// Other events don't change a checkout.
			}
		}
		return events;
	}

	@Override
	public Optional<ReturnResult> verifyReturn(Map<String, String> query) {
		requireConfigured(config.keySecret(), "RAZORPAY_KEY_SECRET");
		String paymentId = query.get("razorpay_payment_id");
		String linkId = query.get("razorpay_payment_link_id");
		String referenceId = query.get("razorpay_payment_link_reference_id");
		String status = query.get("razorpay_payment_link_status");
		String signature = query.get("razorpay_signature");
		if (paymentId == null || linkId == null || referenceId == null || status == null || signature == null) {
			return Optional.empty();
		}
		String expected = Hmac.sha256Hex(config.keySecret(), linkId + "|" + referenceId + "|" + status + "|" + paymentId);
		if (!Hmac.equalHex(expected, signature)) {
			return Optional.empty();
		}
		return Optional.of(new ReturnResult(linkId, "paid".equals(status), paymentId));
	}

	private String errorDescription(String body) {
		try {
			ErrorBody error = json.readValue(body, ErrorBody.class);
			if (error.error() != null && error.error().description() != null) {
				return error.error().description();
			}
		}
		catch (JacksonException ignored) {
			// fall through to a generic message
		}
		return "no error description";
	}

	private static void requireConfigured(String value, String env) {
		if (value == null || value.isBlank()) {
			throw new ProviderException(env + " is not set");
		}
	}

	// Razorpay's JSON, read with the app-wide snake_case naming (short_url -> shortUrl).
	record PaymentLink(String id, String shortUrl, String status) {
	}

	record Webhook(String event, Payload payload) {
	}

	record Payload(Wrapper<Entity> paymentLink, Wrapper<Entity> payment, Wrapper<Entity> refund, Wrapper<Entity> dispute) {
	}

	record Wrapper<T>(T entity) {
	}

	record Entity(String id, String paymentId, Long amount, Long amountRefunded, String status) {
	}

	record ErrorBody(ErrorDetail error) {
	}

	record ErrorDetail(String code, String description) {
	}
}
