package io.github.jiveshanand007.payments.outbox;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import io.github.jiveshanand007.payments.config.PaymentsProperties;
import io.github.jiveshanand007.payments.config.PaymentsProperties.Site;
import io.github.jiveshanand007.payments.outbox.OutboxRepository.Delivery;
import io.github.jiveshanand007.payments.site.SiteRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers queued webhooks to websites, retrying with exponential backoff until they answer 2xx. */
@Component
public class OutboxDispatcher {

	private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
	private static final Duration FIRST_RETRY = Duration.ofSeconds(5);
	private static final Duration MAX_RETRY = Duration.ofHours(1);

	private final OutboxRepository outbox;
	private final SiteRegistry sites;
	private final HttpClient http;
	private final Clock clock;
	private final PaymentsProperties.Outbox config;

	public OutboxDispatcher(OutboxRepository outbox, SiteRegistry sites, HttpClient http, Clock clock,
			PaymentsProperties properties) {
		this.outbox = outbox;
		this.sites = sites;
		this.http = http;
		this.clock = clock;
		this.config = properties.outbox();
	}

	@Scheduled(fixedDelayString = "${payments.outbox.poll-interval:PT2S}")
	public void dispatch() {
		Instant now = clock.instant();
		Instant leaseUntil = now.plus(config.timeout().multipliedBy(4));
		for (Delivery delivery : outbox.claimDue(config.batchSize(), now, leaseUntil)) {
			deliver(delivery);
		}
	}

	private void deliver(Delivery d) {
		Site site = sites.find(d.siteId()).orElse(null);
		if (site == null || site.webhookUrl() == null) {
			outbox.markFailed(d.id(), "site '" + d.siteId() + "' has no webhook-url configured");
			return;
		}
		long timestamp = clock.instant().getEpochSecond();
		HttpRequest request = HttpRequest.newBuilder(site.webhookUrl())
			.timeout(config.timeout())
			.header("Content-Type", "application/json")
			.header("Payments-Event-Id", d.eventId())
			.header("Payments-Event-Type", d.eventType())
			.header(WebhookSigner.HEADER, WebhookSigner.sign(site.webhookSecret(), timestamp, d.payload()))
			.POST(HttpRequest.BodyPublishers.ofString(d.payload()))
			.build();

		String error;
		try {
			HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
			if (response.statusCode() / 100 == 2) {
				outbox.markDelivered(d.id(), clock.instant());
				return;
			}
			error = "site answered HTTP " + response.statusCode();
		}
		catch (IOException e) {
			error = "site unreachable: " + e.getClass().getSimpleName();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			error = "interrupted";
		}

		if (d.attempts() >= config.maxAttempts()) {
			log.warn("Giving up on webhook {} for site {} after {} attempts: {}", d.eventId(), d.siteId(), d.attempts(), error);
			outbox.markFailed(d.id(), error);
		}
		else {
			outbox.scheduleRetry(d.id(), clock.instant().plus(backoff(d.attempts())), error);
		}
	}

	/** 5s, 10s, 20s, … capped at an hour. */
	static Duration backoff(int attempts) {
		int exponent = Math.min(Math.max(attempts - 1, 0), 20);
		Duration delay = FIRST_RETRY.multipliedBy(1L << exponent);
		return delay.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : delay;
	}
}
