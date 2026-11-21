package io.github.jiveshanand007.payments.outbox;

import io.github.jiveshanand007.payments.util.Hmac;

/**
 * Signs webhooks sent to websites: {@code Payments-Signature: t=<unix seconds>,v1=<hex>} where
 * v1 = HMAC-SHA256(secret, t + "." + body). The timestamp lets a site reject old replays.
 */
public final class WebhookSigner {

	public static final String HEADER = "Payments-Signature";

	private WebhookSigner() {
	}

	public static String sign(String secret, long timestampSeconds, String body) {
		return "t=" + timestampSeconds + ",v1=" + Hmac.sha256Hex(secret, timestampSeconds + "." + body);
	}

	/** The check a receiving site performs (also what the README's examples implement). */
	public static boolean verify(String secret, String header, String body, long nowSeconds, long toleranceSeconds) {
		if (header == null) {
			return false;
		}
		Long t = null;
		String v1 = null;
		for (String part : header.split(",")) {
			String[] kv = part.trim().split("=", 2);
			if (kv.length != 2) {
				continue;
			}
			if (kv[0].equals("t")) {
				try {
					t = Long.parseLong(kv[1]);
				}
				catch (NumberFormatException e) {
					return false;
				}
			}
			else if (kv[0].equals("v1")) {
				v1 = kv[1];
			}
		}
		if (t == null || v1 == null || Math.abs(nowSeconds - t) > toleranceSeconds) {
			return false;
		}
		return Hmac.equalHex(Hmac.sha256Hex(secret, t + "." + body), v1);
	}
}
