package io.github.jiveshanand007.payments.api;

import java.io.IOException;

import io.github.jiveshanand007.payments.config.PaymentsProperties.Site;
import io.github.jiveshanand007.payments.site.SiteRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Websites call {@code /v1/checkouts} from their server with {@code Authorization: Bearer <api key>}.
 * Provider webhooks and customer redirects are authenticated by signatures instead.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

	public static final String SITE_ATTRIBUTE = "payments.site";

	private final SiteRegistry sites;

	public ApiKeyFilter(SiteRegistry sites) {
		this.sites = sites;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/v1/checkouts");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		String key = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
		Site site = sites.authenticate(key).orElse(null);
		if (site == null) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType("application/problem+json");
			response.getWriter().write("""
					{"type":"about:blank","title":"Unauthorized","status":401,"code":"invalid_api_key",\
					"detail":"Send the site's API key as Authorization: Bearer <key>"}""");
			return;
		}
		request.setAttribute(SITE_ATTRIBUTE, site);
		chain.doFilter(request, response);
	}
}
