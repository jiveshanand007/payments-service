package io.github.jiveshanand007.payments.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

	private final JdbcClient jdbc;

	public OutboxRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void enqueue(String eventId, String siteId, String checkoutId, String eventType, String payload) {
		jdbc.sql("""
				INSERT INTO webhook_deliveries (event_id, site_id, checkout_id, event_type, payload)
				VALUES (:eventId, :siteId, :checkoutId, :eventType, :payload)""")
			.param("eventId", eventId)
			.param("siteId", siteId)
			.param("checkoutId", checkoutId)
			.param("eventType", eventType)
			.param("payload", payload)
			.update();
	}

	/**
	 * Claims due deliveries for one dispatcher. SKIP LOCKED lets several instances share the
	 * queue without sending the same event twice at once, and the lease (a pushed-back
	 * next_attempt_at) hands a delivery to someone else if this instance dies mid-send.
	 */
	public List<Delivery> claimDue(int batchSize, Instant now, Instant leaseUntil) {
		return jdbc.sql("""
				UPDATE webhook_deliveries
				SET attempts = attempts + 1, next_attempt_at = :leaseUntil
				WHERE id IN (
				    SELECT id FROM webhook_deliveries
				    WHERE status = 'pending' AND next_attempt_at <= :now
				    ORDER BY next_attempt_at
				    LIMIT :batch
				    FOR UPDATE SKIP LOCKED)
				RETURNING id, event_id, site_id, checkout_id, event_type, payload, attempts""")
			.param("now", Timestamp.from(now))
			.param("leaseUntil", Timestamp.from(leaseUntil))
			.param("batch", batchSize)
			.query((rs, row) -> new Delivery(
					rs.getLong("id"),
					rs.getString("event_id"),
					rs.getString("site_id"),
					rs.getString("checkout_id"),
					rs.getString("event_type"),
					rs.getString("payload"),
					rs.getInt("attempts")))
			.list();
	}

	public void markDelivered(long id, Instant now) {
		jdbc.sql("UPDATE webhook_deliveries SET status = 'delivered', delivered_at = :now, last_error = NULL WHERE id = :id")
			.param("id", id)
			.param("now", Timestamp.from(now))
			.update();
	}

	public void scheduleRetry(long id, Instant nextAttemptAt, String error) {
		jdbc.sql("UPDATE webhook_deliveries SET next_attempt_at = :next, last_error = :error WHERE id = :id")
			.param("id", id)
			.param("next", Timestamp.from(nextAttemptAt))
			.param("error", truncate(error))
			.update();
	}

	public void markFailed(long id, String error) {
		jdbc.sql("UPDATE webhook_deliveries SET status = 'failed', last_error = :error WHERE id = :id")
			.param("id", id)
			.param("error", truncate(error))
			.update();
	}

	private static String truncate(String error) {
		return error == null || error.length() <= 500 ? error : error.substring(0, 500);
	}

	public record Delivery(long id, String eventId, String siteId, String checkoutId, String eventType, String payload,
			int attempts) {
	}
}
