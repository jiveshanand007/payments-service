package io.github.jiveshanand007.payments.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 and SHA-256 helpers, with constant-time comparison for anything secret. */
public final class Hmac {

	private static final HexFormat HEX = HexFormat.of();

	private Hmac() {
	}

	public static String sha256Hex(String secret, String message) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HEX.formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 unavailable", e);
		}
	}

	public static String sha256Hex(String value) {
		try {
			return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	/** Compares two hex strings without leaking, through timing, how much of them matched. */
	public static boolean equalHex(String expected, String actual) {
		if (expected == null || actual == null) {
			return false;
		}
		return MessageDigest.isEqual(expected.toLowerCase().getBytes(StandardCharsets.US_ASCII),
				actual.toLowerCase().getBytes(StandardCharsets.US_ASCII));
	}
}
