package io.github.jiveshanand007.payments.util;

import java.security.SecureRandom;

/** Short, unguessable, prefixed ids like {@code chk_4f9QzK...}, readable in logs and support tickets. */
public final class Ids {

	private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
	private static final SecureRandom RANDOM = new SecureRandom();

	private Ids() {
	}

	public static String checkout() {
		return random("chk_");
	}

	public static String event() {
		return random("evt_");
	}

	private static String random(String prefix) {
		StringBuilder id = new StringBuilder(prefix);
		for (int i = 0; i < 22; i++) {
			id.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
		}
		return id.toString();
	}
}
