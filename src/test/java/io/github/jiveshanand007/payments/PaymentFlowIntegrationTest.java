package io.github.jiveshanand007.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.jiveshanand007.payments.outbox.OutboxDispatcher;
import io.github.jiveshanand007.payments.outbox.WebhookSigner;
import io.github.jiveshanand007.payments.util.Hmac;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole path a website and a customer take, against a real Postgres and stand-ins for
 * Razorpay and the website, so it needs no keys and no network.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class PaymentFlowIntegrationTest {

	private static final String API_KEY = "sk_test_site_key_123";
	private static final String SITE_WEBHOOK_SECRET = "whsec_site_secret_0123456789";
	private static final String RZP_KEY_SECRET = "rzp_test_key_secret";
	private static final String RZP_WEBHOOK_SECRET = "rzp_test_webhook_secret";
	private static final String SHOP = "https://shop.example";

	/** Stand-in for Razorpay's API and for the website's webhook endpoint. */
	static final HttpServer STUB;
	static final ConcurrentLinkedDeque<String> RAZORPAY_REQUESTS = new ConcurrentLinkedDeque<>();
	static final ConcurrentLinkedDeque<Map<String, String>> SITE_WEBHOOKS = new ConcurrentLinkedDeque<>();
	static final AtomicInteger LINKS = new AtomicInteger();
	static volatile int siteStatus = 200;

	static {
		try {
			STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		STUB.createContext("/v1/payment_links", ex -> {
			RAZORPAY_REQUESTS.add(read(ex.getRequestBody()));
			int n = LINKS.incrementAndGet();
			respond(ex, 200, "{\"id\":\"plink_" + n + "\",\"short_url\":\"https://rzp.io/i/" + n + "\",\"status\":\"created\"}");
		});
		STUB.createContext("/site-webhook", ex -> {
			SITE_WEBHOOKS.add(Map.of(
					"body", read(ex.getRequestBody()),
					"signature", ex.getRequestHeaders().getFirst(WebhookSigner.HEADER),
					"type", ex.getRequestHeaders().getFirst("Payments-Event-Type")));
			respond(ex, siteStatus, "");
		});
		STUB.start();
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		String stub = "http://127.0.0.1:" + STUB.getAddress().getPort();
		registry.add("payments.public-base-url", () -> "https://pay.example");
		registry.add("payments.razorpay.base-url", () -> stub);
		registry.add("payments.razorpay.key-id", () -> "rzp_test_key");
		registry.add("payments.razorpay.key-secret", () -> RZP_KEY_SECRET);
		registry.add("payments.razorpay.webhook-secret", () -> RZP_WEBHOOK_SECRET);
		registry.add("payments.outbox.poll-interval", () -> "PT1H"); // the test drives the dispatcher itself
		registry.add("payments.sites[0].id", () -> "shop");
		registry.add("payments.sites[0].api-key-sha256", () -> Hmac.sha256Hex(API_KEY));
		registry.add("payments.sites[0].webhook-url", () -> stub + "/site-webhook");
		registry.add("payments.sites[0].webhook-secret", () -> SITE_WEBHOOK_SECRET);
		registry.add("payments.sites[0].allowed-redirect-origins[0]", () -> SHOP);
	}

	@AfterAll
	static void stopStub() {
		STUB.stop(0);
	}

	@Autowired
	Environment env;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	OutboxDispatcher dispatcher;

	private final HttpClient http = HttpClient.newHttpClient();
	private final ObjectMapper json = JsonMapper.builder().build();

	@BeforeEach
	void reset() {
		RAZORPAY_REQUESTS.clear();
		SITE_WEBHOOKS.clear();
		siteStatus = 200;
	}

	// ---- creating a checkout ------------------------------------------------------------------

	@Test
	void createsAHostedCheckoutAndSendsRazorpayNoCustomerDetails() throws Exception {
		HttpResponse<String> res = createCheckout("order-1", null);

		assertThat(res.statusCode()).isEqualTo(201);
		Map<String, Object> checkout = parse(res.body());
		assertThat(checkout).containsEntry("status", "created")
			.containsEntry("reference", "order-1")
			.containsEntry("amount_minor", 99900)
			.containsEntry("currency", "INR");
		assertThat((String) checkout.get("checkout_url")).startsWith("https://rzp.io/i/");

		Map<String, Object> sent = parse(RAZORPAY_REQUESTS.getLast());
		assertThat(sent).containsEntry("amount", 99900)
			.containsEntry("reference_id", checkout.get("id"))
			.containsEntry("callback_url", "https://pay.example/v1/return/" + checkout.get("id"))
			.containsEntry("notify", Map.of("sms", false, "email", false))
			.doesNotContainKey("customer");
	}

	@Test
	void refusesRequestsWithoutAValidApiKey() throws Exception {
		HttpResponse<String> res = send(HttpRequest.newBuilder(url("/v1/checkouts"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer wrong-key")
			.POST(HttpRequest.BodyPublishers.ofString(checkoutBody("order-x", SHOP + "/ok"))));
		assertThat(res.statusCode()).isEqualTo(401);
		assertThat(parse(res.body())).containsEntry("code", "invalid_api_key");
	}

	@Test
	void refusesReturnUrlsOffTheSitesOrigins() throws Exception {
		HttpResponse<String> res = send(authed("/v1/checkouts", null)
			.POST(HttpRequest.BodyPublishers.ofString(checkoutBody("order-2", "https://evil.example/ok"))));
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(parse(res.body())).containsEntry("code", "redirect_not_allowed");
	}

	@Test
	void validatesTheRequest() throws Exception {
		HttpResponse<String> res = send(authed("/v1/checkouts", null)
			.POST(HttpRequest.BodyPublishers.ofString("{\"reference\":\"r\",\"item\":\"x\",\"amount_minor\":-5,"
					+ "\"success_url\":\"" + SHOP + "/ok\",\"cancel_url\":\"" + SHOP + "/no\"}")));
		assertThat(res.statusCode()).isEqualTo(400);
	}

	@Test
	void anIdempotencyKeyReturnsTheSameCheckoutAndRefusesADifferentRequest() throws Exception {
		HttpResponse<String> first = createCheckout("order-3", "key-3");
		HttpResponse<String> again = createCheckout("order-3", "key-3");

		assertThat(first.statusCode()).isEqualTo(201);
		assertThat(again.statusCode()).isEqualTo(200);
		assertThat(parse(again.body()).get("id")).isEqualTo(parse(first.body()).get("id"));
		assertThat(RAZORPAY_REQUESTS).hasSize(1);

		HttpResponse<String> different = createCheckout("order-4", "key-3");
		assertThat(different.statusCode()).isEqualTo(409);
		assertThat(parse(different.body())).containsEntry("code", "idempotency_key_reused");
	}

	@Test
	void anUnknownCheckoutIsNotFound() throws Exception {
		HttpResponse<String> res = send(authed("/v1/checkouts/chk_does_not_exist", null).GET());
		assertThat(res.statusCode()).isEqualTo(404);
	}

	// ---- provider webhooks --------------------------------------------------------------------

	@Test
	void aPaidWebhookMarksTheCheckoutPaidExactlyOnce() throws Exception {
		Map<String, Object> checkout = parse(createCheckout("order-5", null).body());
		String linkId = linkIdOf(checkout);

		String body = paidWebhook(linkId, "pay_5");
		assertThat(razorpayWebhook("evt_rzp_5", body, sign(body)).statusCode()).isEqualTo(200);
		assertThat(razorpayWebhook("evt_rzp_5", body, sign(body)).statusCode()).isEqualTo(200); // Razorpay retry

		Map<String, Object> after = parse(send(authed("/v1/checkouts/" + checkout.get("id"), null).GET()).body());
		assertThat(after).containsEntry("status", "paid");
		assertThat(after.get("paid_at")).isNotNull();
		assertThat(deliveriesFor((String) checkout.get("id"))).isEqualTo(1);
	}

	@Test
	void aWebhookWithABadSignatureChangesNothing() throws Exception {
		Map<String, Object> checkout = parse(createCheckout("order-6", null).body());
		String body = paidWebhook(linkIdOf(checkout), "pay_6");

		assertThat(razorpayWebhook("evt_rzp_6", body, "00".repeat(32)).statusCode()).isEqualTo(401);
		assertThat(statusOf((String) checkout.get("id"))).isEqualTo("created");
	}

	@Test
	void refundsAndDisputesFollowThePaymentAndExpiredCheckoutsStayExpired() throws Exception {
		Map<String, Object> paid = parse(createCheckout("order-7", null).body());
		String body = paidWebhook(linkIdOf(paid), "pay_7");
		razorpayWebhook("evt_rzp_7a", body, sign(body));

		String refund = """
				{"event":"refund.processed","payload":{"refund":{"entity":{"id":"rfnd_7","payment_id":"pay_7"}},
				 "payment":{"entity":{"id":"pay_7","amount":99900,"amount_refunded":99900}}}}""";
		razorpayWebhook("evt_rzp_7b", refund, sign(refund));
		assertThat(statusOf((String) paid.get("id"))).isEqualTo("refunded");

		Map<String, Object> expired = parse(createCheckout("order-8", null).body());
		String expiry = """
				{"event":"payment_link.expired","payload":{"payment_link":{"entity":{"id":"%s"}}}}"""
			.formatted(linkIdOf(expired));
		razorpayWebhook("evt_rzp_8a", expiry, sign(expiry));
		String latePayment = paidWebhook(linkIdOf(expired), "pay_8");
		razorpayWebhook("evt_rzp_8b", latePayment, sign(latePayment));
		assertThat(statusOf((String) expired.get("id"))).isEqualTo("expired");
	}

	// ---- telling the website ------------------------------------------------------------------

	@Test
	void theWebsiteGetsASignedWebhookAndFailedDeliveriesAreRetried() throws Exception {
		Map<String, Object> checkout = parse(createCheckout("order-9", null).body());
		String body = paidWebhook(linkIdOf(checkout), "pay_9");
		razorpayWebhook("evt_rzp_9", body, sign(body));

		siteStatus = 500; // the website is down
		dispatcher.dispatch();
		Map<String, Object> pending = delivery((String) checkout.get("id"));
		assertThat(pending).containsEntry("status", "pending").containsEntry("attempts", 1);
		assertThat((String) pending.get("last_error")).contains("500");

		siteStatus = 200; // it's back; make the retry due now instead of waiting for the backoff
		jdbc.sql("UPDATE webhook_deliveries SET next_attempt_at = now() WHERE checkout_id = :id")
			.param("id", checkout.get("id"))
			.update();
		dispatcher.dispatch();
		assertThat(delivery((String) checkout.get("id"))).containsEntry("status", "delivered");

		Map<String, String> received = SITE_WEBHOOKS.getLast();
		assertThat(received.get("type")).isEqualTo("checkout.paid");
		assertThat(WebhookSigner.verify(SITE_WEBHOOK_SECRET, received.get("signature"), received.get("body"),
				Instant.now().getEpochSecond(), 300)).isTrue();
		Map<String, Object> event = parse(received.get("body"));
		assertThat(event).containsEntry("type", "checkout.paid");
		@SuppressWarnings("unchecked")
		Map<String, Object> data = (Map<String, Object>) event.get("data");
		assertThat(data).containsEntry("id", checkout.get("id")).containsEntry("reference", "order-9")
			.containsEntry("status", "paid");
	}

	// ---- the customer coming back -------------------------------------------------------------

	@Test
	void aSignedPaidRedirectSendsTheCustomerToTheSuccessPage() throws Exception {
		Map<String, Object> checkout = parse(createCheckout("order-10", null).body());
		String id = (String) checkout.get("id");
		String linkId = linkIdOf(checkout);
		String signature = Hmac.sha256Hex(RZP_KEY_SECRET, linkId + "|" + id + "|paid|pay_10");

		HttpResponse<String> res = send(HttpRequest.newBuilder(url("/v1/return/" + id
				+ "?razorpay_payment_id=pay_10&razorpay_payment_link_id=" + linkId
				+ "&razorpay_payment_link_reference_id=" + id
				+ "&razorpay_payment_link_status=paid&razorpay_signature=" + signature)).GET());

		assertThat(res.statusCode()).isEqualTo(303);
		assertThat(res.headers().firstValue("Location")).contains(SHOP + "/thanks?checkout_id=" + id);
		assertThat(statusOf(id)).isEqualTo("paid");
	}

	@Test
	void aTamperedRedirectGoesToTheCancelPageAndPaysNothing() throws Exception {
		Map<String, Object> checkout = parse(createCheckout("order-11", null).body());
		String id = (String) checkout.get("id");

		HttpResponse<String> res = send(HttpRequest.newBuilder(url("/v1/return/" + id
				+ "?razorpay_payment_id=pay_11&razorpay_payment_link_id=" + linkIdOf(checkout)
				+ "&razorpay_payment_link_reference_id=" + id
				+ "&razorpay_payment_link_status=paid&razorpay_signature=" + "ab".repeat(32))).GET());

		assertThat(res.statusCode()).isEqualTo(303);
		assertThat(res.headers().firstValue("Location")).contains(SHOP + "/cancelled?checkout_id=" + id);
		assertThat(statusOf(id)).isEqualTo("created");
	}

	// ---- helpers ------------------------------------------------------------------------------

	private HttpResponse<String> createCheckout(String reference, String idempotencyKey) throws Exception {
		return send(authed("/v1/checkouts", idempotencyKey)
			.POST(HttpRequest.BodyPublishers.ofString(checkoutBody(reference, SHOP + "/thanks"))));
	}

	private static String checkoutBody(String reference, String successUrl) {
		return """
				{"reference":"%s","item":"30-min consultation","amount_minor":99900,
				 "success_url":"%s","cancel_url":"%s/cancelled"}""".formatted(reference, successUrl, SHOP);
	}

	private HttpRequest.Builder authed(String path, String idempotencyKey) {
		HttpRequest.Builder b = HttpRequest.newBuilder(url(path))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer " + API_KEY);
		return idempotencyKey == null ? b : b.header("Idempotency-Key", idempotencyKey);
	}

	private HttpResponse<String> razorpayWebhook(String eventId, String body, String signature) throws Exception {
		return send(HttpRequest.newBuilder(url("/v1/webhooks/razorpay"))
			.header("Content-Type", "application/json")
			.header("X-Razorpay-Event-Id", eventId)
			.header("X-Razorpay-Signature", signature)
			.POST(HttpRequest.BodyPublishers.ofString(body)));
	}

	private static String paidWebhook(String linkId, String paymentId) {
		return """
				{"event":"payment_link.paid","payload":{"payment_link":{"entity":{"id":"%s","status":"paid"}},
				 "payment":{"entity":{"id":"%s","amount":99900}}}}""".formatted(linkId, paymentId);
	}

	private static String sign(String body) {
		return Hmac.sha256Hex(RZP_WEBHOOK_SECRET, body);
	}

	private String linkIdOf(Map<String, Object> checkout) {
		return jdbc.sql("SELECT provider_ref FROM checkouts WHERE id = :id")
			.param("id", checkout.get("id"))
			.query(String.class)
			.single();
	}

	private String statusOf(String id) {
		return jdbc.sql("SELECT status FROM checkouts WHERE id = :id").param("id", id).query(String.class).single();
	}

	private int deliveriesFor(String checkoutId) {
		return jdbc.sql("SELECT count(*) FROM webhook_deliveries WHERE checkout_id = :id")
			.param("id", checkoutId)
			.query(Integer.class)
			.single();
	}

	private Map<String, Object> delivery(String checkoutId) {
		return jdbc.sql("SELECT status, attempts, last_error FROM webhook_deliveries WHERE checkout_id = :id")
			.param("id", checkoutId)
			.query()
			.singleRow();
	}

	private URI url(String path) {
		return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> parse(String body) {
		return json.readValue(body, Map.class);
	}

	private static String read(InputStream in) throws IOException {
		return new String(in.readAllBytes(), StandardCharsets.UTF_8);
	}

	private static void respond(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			ex.getResponseBody().write(bytes);
		}
		ex.close();
	}
}
