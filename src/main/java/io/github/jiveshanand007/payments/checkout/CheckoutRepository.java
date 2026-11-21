package io.github.jiveshanand007.payments.checkout;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CheckoutRepository {

	private static final String COLUMNS = """
			id, site_id, reference, item, amount_minor, currency, status, provider, provider_ref,
			provider_payment_id, checkout_url, success_url, cancel_url, idempotency_key, request_hash,
			expires_at, created_at, updated_at, paid_at""";

	private final JdbcClient jdbc;

	public CheckoutRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(Checkout c) {
		jdbc.sql("""
				INSERT INTO checkouts (id, site_id, reference, item, amount_minor, currency, status, provider,
				    provider_ref, checkout_url, success_url, cancel_url, idempotency_key, request_hash, expires_at)
				VALUES (:id, :siteId, :reference, :item, :amountMinor, :currency, :status, :provider,
				    :providerRef, :checkoutUrl, :successUrl, :cancelUrl, :idempotencyKey, :requestHash, :expiresAt)""")
			.param("id", c.id())
			.param("siteId", c.siteId())
			.param("reference", c.reference())
			.param("item", c.item())
			.param("amountMinor", c.amountMinor())
			.param("currency", c.currency())
			.param("status", c.status().wire())
			.param("provider", c.provider())
			.param("providerRef", c.providerRef())
			.param("checkoutUrl", c.checkoutUrl())
			.param("successUrl", c.successUrl())
			.param("cancelUrl", c.cancelUrl())
			.param("idempotencyKey", c.idempotencyKey())
			.param("requestHash", c.requestHash())
			.param("expiresAt", Timestamp.from(c.expiresAt()))
			.update();
	}

	public Optional<Checkout> find(String id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM checkouts WHERE id = :id")
			.param("id", id)
			.query(CheckoutRepository::map)
			.optional();
	}

	public Optional<Checkout> findForSite(String siteId, String id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM checkouts WHERE id = :id AND site_id = :siteId")
			.param("id", id)
			.param("siteId", siteId)
			.query(CheckoutRepository::map)
			.optional();
	}

	public Optional<Checkout> findByIdempotencyKey(String siteId, String key) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM checkouts WHERE site_id = :siteId AND idempotency_key = :key")
			.param("siteId", siteId)
			.param("key", key)
			.query(CheckoutRepository::map)
			.optional();
	}

	/** Locks the row so concurrent events for one checkout apply one at a time. */
	public Optional<Checkout> lockByProviderRef(String provider, String providerRef) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM checkouts WHERE provider = :provider AND provider_ref = :ref FOR UPDATE")
			.param("provider", provider)
			.param("ref", providerRef)
			.query(CheckoutRepository::map)
			.optional();
	}

	public Optional<Checkout> lockByProviderPaymentId(String provider, String paymentId) {
		return jdbc.sql("SELECT " + COLUMNS
				+ " FROM checkouts WHERE provider = :provider AND provider_payment_id = :paymentId FOR UPDATE")
			.param("provider", provider)
			.param("paymentId", paymentId)
			.query(CheckoutRepository::map)
			.optional();
	}

	public void updateStatus(String id, CheckoutStatus status, String providerPaymentId, Instant now) {
		jdbc.sql("""
				UPDATE checkouts
				SET status = :status,
				    provider_payment_id = COALESCE(:paymentId, provider_payment_id),
				    paid_at = CASE WHEN :status = 'paid' THEN :now ELSE paid_at END,
				    updated_at = :now
				WHERE id = :id""")
			.param("id", id)
			.param("status", status.wire())
			.param("paymentId", providerPaymentId)
			.param("now", Timestamp.from(now))
			.update();
	}

	private static Checkout map(ResultSet rs, int row) throws SQLException {
		return new Checkout(
				rs.getString("id"),
				rs.getString("site_id"),
				rs.getString("reference"),
				rs.getString("item"),
				rs.getLong("amount_minor"),
				rs.getString("currency"),
				CheckoutStatus.fromWire(rs.getString("status")),
				rs.getString("provider"),
				rs.getString("provider_ref"),
				rs.getString("provider_payment_id"),
				rs.getString("checkout_url"),
				rs.getString("success_url"),
				rs.getString("cancel_url"),
				rs.getString("idempotency_key"),
				rs.getString("request_hash"),
				instant(rs, "expires_at"),
				instant(rs, "created_at"),
				instant(rs, "updated_at"),
				instant(rs, "paid_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp ts = rs.getTimestamp(column);
		return ts == null ? null : ts.toInstant();
	}
}
