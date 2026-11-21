package io.github.jiveshanand007.payments.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HmacTest {

	@Test
	void matchesTheRfcStyleReferenceVector() {
		assertThat(Hmac.sha256Hex("key", "The quick brown fox jumps over the lazy dog"))
			.isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
	}

	@Test
	void hashesWithSha256() {
		assertThat(Hmac.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

	@Test
	void comparesHexCaseInsensitivelyAndRejectsNulls() {
		assertThat(Hmac.equalHex("ABcd", "abCD")).isTrue();
		assertThat(Hmac.equalHex("abcd", "abce")).isFalse();
		assertThat(Hmac.equalHex("abcd", "abcdef")).isFalse();
		assertThat(Hmac.equalHex(null, "abcd")).isFalse();
	}

	@Test
	void idsArePrefixedAndUnique() {
		assertThat(Ids.checkout()).startsWith("chk_").hasSize(26).isNotEqualTo(Ids.checkout());
		assertThat(Ids.event()).startsWith("evt_");
	}
}
