package io.github.jiveshanand007.payments.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class WebhookSignerTest {

	private static final String SECRET = "whsec_0123456789abcdef";
	private static final String BODY = "{\"type\":\"checkout.paid\"}";

	@Test
	void aSignedBodyVerifies() {
		String header = WebhookSigner.sign(SECRET, 1_700_000_000L, BODY);
		assertThat(header).startsWith("t=1700000000,v1=");
		assertThat(WebhookSigner.verify(SECRET, header, BODY, 1_700_000_010L, 300)).isTrue();
	}

	@Test
	void aChangedBodyOrWrongSecretFails() {
		String header = WebhookSigner.sign(SECRET, 1_700_000_000L, BODY);
		assertThat(WebhookSigner.verify(SECRET, header, BODY.replace("paid", "refunded"), 1_700_000_000L, 300)).isFalse();
		assertThat(WebhookSigner.verify("another_secret_xx", header, BODY, 1_700_000_000L, 300)).isFalse();
	}

	@Test
	void anOldSignatureIsRejectedAsAReplay() {
		String header = WebhookSigner.sign(SECRET, 1_700_000_000L, BODY);
		assertThat(WebhookSigner.verify(SECRET, header, BODY, 1_700_000_000L + 301, 300)).isFalse();
	}

	@Test
	void malformedHeadersFail() {
		assertThat(WebhookSigner.verify(SECRET, null, BODY, 0, 300)).isFalse();
		assertThat(WebhookSigner.verify(SECRET, "t=abc,v1=00", BODY, 0, 300)).isFalse();
		assertThat(WebhookSigner.verify(SECRET, "v1=00", BODY, 0, 300)).isFalse();
	}

	@Test
	void retriesBackOffExponentiallyUpToAnHour() {
		assertThat(OutboxDispatcher.backoff(1)).isEqualTo(Duration.ofSeconds(5));
		assertThat(OutboxDispatcher.backoff(2)).isEqualTo(Duration.ofSeconds(10));
		assertThat(OutboxDispatcher.backoff(4)).isEqualTo(Duration.ofSeconds(40));
		assertThat(OutboxDispatcher.backoff(30)).isEqualTo(Duration.ofHours(1));
	}
}
