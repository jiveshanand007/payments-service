package io.github.jiveshanand007.payments.checkout;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * What a website sends to start a checkout. The amount is in the currency's smallest unit
 * (paise for INR), so ₹999 is 99900 and no floating point is involved.
 */
public record CreateCheckoutRequest(
		@NotBlank @Size(max = 64) String reference,
		@NotBlank @Size(max = 200) String item,
		@Positive @Max(100_000_000_00L) long amountMinor,
		@Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like INR") String currency,
		@NotBlank @Size(max = 2048) String successUrl,
		@NotBlank @Size(max = 2048) String cancelUrl) {

	public String currencyOrDefault() {
		return currency == null ? "INR" : currency;
	}
}
