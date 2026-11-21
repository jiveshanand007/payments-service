package io.github.jiveshanand007.payments.site;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;

import io.github.jiveshanand007.payments.config.PaymentsProperties;
import io.github.jiveshanand007.payments.config.PaymentsProperties.Site;
import io.github.jiveshanand007.payments.util.Hmac;
import org.junit.jupiter.api.Test;

class SiteRegistryTest {

	private static final String KEY = "sk_test_portfolio_key";
	private static final Site SITE = new Site("portfolio", Hmac.sha256Hex(KEY), URI.create("https://shop.example/hook"),
			"whsec_0123456789abcdef", List.of("https://shop.example", "http://localhost:3000"));

	private static SiteRegistry registry(Site... sites) {
		return new SiteRegistry(new PaymentsProperties(null, null, null, null, List.of(sites)));
	}

	@Test
	void findsASiteByItsApiKey() {
		SiteRegistry registry = registry(SITE);
		assertThat(registry.authenticate(KEY)).contains(SITE);
		assertThat(registry.authenticate("wrong")).isEmpty();
		assertThat(registry.authenticate(null)).isEmpty();
	}

	@Test
	void redirectsMustStayOnTheSitesOwnOrigins() {
		assertThat(SiteRegistry.redirectAllowed(SITE, "https://shop.example/thanks?x=1")).isTrue();
		assertThat(SiteRegistry.redirectAllowed(SITE, "https://SHOP.example:443/thanks")).isTrue();
		assertThat(SiteRegistry.redirectAllowed(SITE, "http://localhost:3000/ok")).isTrue();

		assertThat(SiteRegistry.redirectAllowed(SITE, "https://evil.example/thanks")).isFalse();
		assertThat(SiteRegistry.redirectAllowed(SITE, "https://shop.example.evil.example/")).isFalse();
		assertThat(SiteRegistry.redirectAllowed(SITE, "http://shop.example/thanks")).isFalse();
		assertThat(SiteRegistry.redirectAllowed(SITE, "javascript:alert(1)")).isFalse();
		assertThat(SiteRegistry.redirectAllowed(SITE, "/relative")).isFalse();
	}

	@Test
	void badConfigurationStopsStartupWithAClearMessage() {
		assertThatThrownBy(() -> registry(SITE, SITE)).hasMessageContaining("configured twice");
		assertThatThrownBy(() -> registry(new Site("x", "not-a-hash", null, null, List.of("https://a.example"))))
			.hasMessageContaining("api-key-sha256");
		assertThatThrownBy(() -> registry(new Site("x", Hmac.sha256Hex(KEY), URI.create("https://a.example/h"), "short",
				List.of("https://a.example"))))
			.hasMessageContaining("webhook-secret");
		assertThatThrownBy(() -> registry(new Site("x", Hmac.sha256Hex(KEY), null, null, List.of())))
			.hasMessageContaining("allowed-redirect-origins");
	}
}
