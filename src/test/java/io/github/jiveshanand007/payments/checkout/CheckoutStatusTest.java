package io.github.jiveshanand007.payments.checkout;

import static io.github.jiveshanand007.payments.checkout.CheckoutStatus.CREATED;
import static io.github.jiveshanand007.payments.checkout.CheckoutStatus.DISPUTED;
import static io.github.jiveshanand007.payments.checkout.CheckoutStatus.EXPIRED;
import static io.github.jiveshanand007.payments.checkout.CheckoutStatus.PAID;
import static io.github.jiveshanand007.payments.checkout.CheckoutStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CheckoutStatusTest {

	@Test
	void moneyOnlyMovesForward() {
		assertThat(CREATED.canMoveTo(PAID)).isTrue();
		assertThat(CREATED.canMoveTo(EXPIRED)).isTrue();
		assertThat(PAID.canMoveTo(REFUNDED)).isTrue();
		assertThat(PAID.canMoveTo(DISPUTED)).isTrue();
		assertThat(DISPUTED.canMoveTo(REFUNDED)).isTrue();
	}

	@Test
	void finishedCheckoutsCannotBePaidAgain() {
		assertThat(EXPIRED.canMoveTo(PAID)).isFalse();
		assertThat(REFUNDED.canMoveTo(PAID)).isFalse();
		assertThat(PAID.canMoveTo(CREATED)).isFalse();
		assertThat(CREATED.canMoveTo(REFUNDED)).isFalse();
	}

	@Test
	void storesAsLowercaseWords() {
		assertThat(PAID.wire()).isEqualTo("paid");
		assertThat(CheckoutStatus.fromWire("disputed")).isEqualTo(DISPUTED);
	}
}
