package io.github.jiveshanand007.payments.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Everything configurable about the service; see application.yml for the environment variables. */
@ConfigurationProperties("payments")
public record PaymentsProperties(
		@DefaultValue("http://localhost:8080") URI publicBaseUrl,
		@DefaultValue("30m") Duration checkoutExpiry,
		@DefaultValue Razorpay razorpay,
		@DefaultValue Outbox outbox,
		List<Site> sites) {

	public PaymentsProperties {
		sites = sites == null ? List.of() : List.copyOf(sites);
	}

	public record Razorpay(
			@DefaultValue("https://api.razorpay.com") URI baseUrl,
			String keyId,
			String keySecret,
			String webhookSecret) {
	}

	public record Outbox(
			@DefaultValue("10") int maxAttempts,
			@DefaultValue("10") int batchSize,
			@DefaultValue("5s") Duration timeout) {
	}

	/**
	 * A website that may use this service. Only a hash of its API key is configured, so the
	 * key itself never sits in this service's config or logs.
	 */
	public record Site(
			String id,
			String apiKeySha256,
			URI webhookUrl,
			String webhookSecret,
			List<String> allowedRedirectOrigins) {

		public Site {
			allowedRedirectOrigins = allowedRedirectOrigins == null ? List.of() : List.copyOf(allowedRedirectOrigins);
		}
	}
}
