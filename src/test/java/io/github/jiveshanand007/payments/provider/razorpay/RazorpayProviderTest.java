package io.github.jiveshanand007.payments.provider.razorpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import io.github.jiveshanand007.payments.checkout.CheckoutStatus;
import io.github.jiveshanand007.payments.config.PaymentsProperties;
import io.github.jiveshanand007.payments.provider.ProviderEvent;
import io.github.jiveshanand007.payments.util.Hmac;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class RazorpayProviderTest {

	private static final String KEY_SECRET = "rzp_key_secret";
	private static final String WEBHOOK_SECRET = "rzp_webhook_secret";

	private final RazorpayProvider provider = new RazorpayProvider(
			new PaymentsProperties(null, null,
					new PaymentsProperties.Razorpay(URI.create("http://unused"), "rzp_test_key", KEY_SECRET, WEBHOOK_SECRET),
					null, List.of()),
			HttpClient.newHttpClient(),
			JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build());

	@Test
	void verifiesWebhookSignaturesOverTheRawBody() {
		String body = "{\"event\":\"payment_link.paid\"}";
		String signature = Hmac.sha256Hex(WEBHOOK_SECRET, body);
		assertThat(provider.verifyWebhook(Map.of("x-razorpay-signature", signature), body)).isTrue();
		assertThat(provider.verifyWebhook(Map.of("x-razorpay-signature", signature), body + " ")).isFalse();
		assertThat(provider.verifyWebhook(Map.of(), body)).isFalse();
	}

	@Test
	void readsAPaidPaymentLink() {
		String body = """
				{"entity":"event","event":"payment_link.paid","payload":{
				  "payment_link":{"entity":{"id":"plink_1","status":"paid","reference_id":"chk_1"}},
				  "payment":{"entity":{"id":"pay_1","amount":99900,"email":"someone@example.com","contact":"+919999999999"}}}}""";
		List<ProviderEvent> events = provider.parseWebhook(Map.of("x-razorpay-event-id", "evt_rzp_1"), body);
		assertThat(events).containsExactly(
				new ProviderEvent("evt_rzp_1", "payment_link.paid", CheckoutStatus.PAID, "plink_1", "pay_1"));
	}

	@Test
	void fullRefundsAndDisputesAreFoundByPaymentId() {
		String refund = """
				{"event":"refund.processed","payload":{
				  "refund":{"entity":{"id":"rfnd_1","payment_id":"pay_1","amount":99900}},
				  "payment":{"entity":{"id":"pay_1","amount":99900,"amount_refunded":99900}}}}""";
		assertThat(provider.parseWebhook(Map.of("x-razorpay-event-id", "e2"), refund))
			.extracting(ProviderEvent::target, ProviderEvent::providerPaymentId)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(CheckoutStatus.REFUNDED, "pay_1"));

		String dispute = """
				{"event":"payment.dispute.created","payload":{"dispute":{"entity":{"id":"disp_1","payment_id":"pay_1"}}}}""";
		assertThat(provider.parseWebhook(Map.of("x-razorpay-event-id", "e3"), dispute))
			.extracting(ProviderEvent::target)
			.containsExactly(CheckoutStatus.DISPUTED);
	}

	@Test
	void aPartialRefundLeavesThePaymentPaid() {
		String body = """
				{"event":"refund.processed","payload":{
				  "refund":{"entity":{"id":"rfnd_1","payment_id":"pay_1","amount":10000}},
				  "payment":{"entity":{"id":"pay_1","amount":99900,"amount_refunded":10000}}}}""";
		assertThat(provider.parseWebhook(Map.of(), body)).isEmpty();
	}

	@Test
	void ignoresEventsThatDoNotChangeACheckout() {
		assertThat(provider.parseWebhook(Map.of(), "{\"event\":\"order.paid\",\"payload\":{}}")).isEmpty();
	}

	@Test
	void verifiesTheCustomersSignedRedirect() {
		String signature = Hmac.sha256Hex(KEY_SECRET, "plink_1|chk_1|paid|pay_1");
		Map<String, String> query = Map.of(
				"razorpay_payment_id", "pay_1",
				"razorpay_payment_link_id", "plink_1",
				"razorpay_payment_link_reference_id", "chk_1",
				"razorpay_payment_link_status", "paid",
				"razorpay_signature", signature);
		assertThat(provider.verifyReturn(query)).hasValueSatisfying(r -> {
			assertThat(r.paid()).isTrue();
			assertThat(r.providerRef()).isEqualTo("plink_1");
		});

		Map<String, String> tampered = new java.util.HashMap<>(query);
		tampered.put("razorpay_payment_link_reference_id", "chk_other");
		assertThat(provider.verifyReturn(tampered)).isEmpty();
	}
}
