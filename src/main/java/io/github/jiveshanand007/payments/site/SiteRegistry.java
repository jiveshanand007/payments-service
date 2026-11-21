package io.github.jiveshanand007.payments.site;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import io.github.jiveshanand007.payments.config.PaymentsProperties;
import io.github.jiveshanand007.payments.config.PaymentsProperties.Site;
import io.github.jiveshanand007.payments.util.Hmac;

import org.springframework.stereotype.Component;

/** The websites allowed to use this service, checked once at startup. */
@Component
public class SiteRegistry {

	private final List<Site> sites;

	public SiteRegistry(PaymentsProperties properties) {
		this.sites = properties.sites();
		Set<String> ids = new HashSet<>();
		for (Site site : sites) {
			require(site.id() != null && !site.id().isBlank(), "a site is missing its id");
			require(ids.add(site.id()), "site id '%s' is configured twice".formatted(site.id()));
			require(site.apiKeySha256() != null && site.apiKeySha256().matches("[0-9a-fA-F]{64}"),
					"site '%s' needs api-key-sha256: the SHA-256 hex of its API key".formatted(site.id()));
			require(site.webhookUrl() == null || (site.webhookSecret() != null && site.webhookSecret().length() >= 16),
					"site '%s' has a webhook-url, so it needs a webhook-secret of at least 16 characters".formatted(site.id()));
			require(!site.allowedRedirectOrigins().isEmpty(),
					"site '%s' needs allowed-redirect-origins, e.g. https://example.com".formatted(site.id()));
		}
	}

	/** The site whose API key this is; the key is hashed and compared in constant time. */
	public Optional<Site> authenticate(String apiKey) {
		if (apiKey == null || apiKey.isBlank()) {
			return Optional.empty();
		}
		String hash = Hmac.sha256Hex(apiKey);
		Site match = null;
		for (Site site : sites) {
			if (Hmac.equalHex(site.apiKeySha256(), hash)) {
				match = site;
			}
		}
		return Optional.ofNullable(match);
	}

	public Optional<Site> find(String id) {
		return sites.stream().filter(s -> s.id().equals(id)).findFirst();
	}

	/**
	 * Success and cancel URLs must point at one of the site's own origins, so the service can't
	 * be used to bounce customers to an arbitrary page.
	 */
	public static boolean redirectAllowed(Site site, String url) {
		URI uri;
		try {
			uri = URI.create(url);
		}
		catch (IllegalArgumentException e) {
			return false;
		}
		if (uri.getScheme() == null || uri.getHost() == null) {
			return false;
		}
		String origin = origin(uri);
		return site.allowedRedirectOrigins().stream().anyMatch(o -> origin.equals(origin(URI.create(o))));
	}

	private static String origin(URI uri) {
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		int port = uri.getPort() != -1 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
		return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
	}

	private static void require(boolean ok, String message) {
		if (!ok) {
			throw new IllegalStateException("payments.sites: " + message);
		}
	}
}
