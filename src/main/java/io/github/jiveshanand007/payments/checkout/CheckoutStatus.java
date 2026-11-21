package io.github.jiveshanand007.payments.checkout;

import java.util.Locale;
import java.util.Set;

/**
 * A checkout's life. Money only moves forward: an expired checkout can't become paid, and a
 * refunded one can't be paid again.
 *
 * <pre>
 * created ──▶ paid ──▶ refunded
 *    │          │          ▲
 *    ▼          ▼          │
 * expired   disputed ──────┘
 * </pre>
 */
public enum CheckoutStatus {

	CREATED, PAID, EXPIRED, REFUNDED, DISPUTED;

	public Set<CheckoutStatus> next() {
		return switch (this) {
			case CREATED -> Set.of(PAID, EXPIRED);
			case PAID -> Set.of(REFUNDED, DISPUTED);
			case DISPUTED -> Set.of(REFUNDED);
			case EXPIRED, REFUNDED -> Set.of();
		};
	}

	public boolean canMoveTo(CheckoutStatus target) {
		return next().contains(target);
	}

	public String wire() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static CheckoutStatus fromWire(String value) {
		return valueOf(value.toUpperCase(Locale.ROOT));
	}
}
