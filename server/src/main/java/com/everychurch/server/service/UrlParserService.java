package com.everychurch.server.service;

import com.everychurch.server.dto.ChurchDto;
import com.everychurch.server.dto.ServiceTimeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fetches a church's website (POST /api/churches/parse-url — any authenticated user,
 * not just staff/admin) to pre-fill the create/edit form from its JSON-LD/meta tags.
 * Any endpoint that fetches a user-supplied URL server-side is a textbook SSRF vector
 * (found in review, 2026-09-22): without a check, someone could point this at
 * http://169.254.169.254/... (cloud instance metadata), an internal admin panel, or
 * anything else only reachable from this server's own network — and the URL doesn't
 * even have to *look* internal at request time, since a public hostname can 30x-redirect
 * to a private one after the fact. validateAndResolve is the guard: every hostname
 * (the original URL and every redirect hop) is DNS-resolved and every resulting address
 * checked against InetAddress's own loopback/link-local/site-local(RFC1918)/
 * multicast/wildcard predicates before this ever connects to it. Redirects are
 * followed manually (not via Jsoup's built-in followRedirects) specifically so each
 * hop gets that same check — Jsoup's own redirect following has no hook for it.
 */
@Service
public class UrlParserService {

    private static final int MAX_REDIRECTS = 5;
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final ObjectMapper mapper = new ObjectMapper();

    public ChurchDto parse(String url) {
        Document doc = fetch(url, MAX_REDIRECTS);
        ChurchDto fromLd = tryJsonLd(doc);
        return fromLd != null ? fromLd : fromMeta(doc);
    }

    private Document fetch(String url, int redirectsRemaining) {
        validateAndResolve(url);

        Connection.Response response;
        try {
            response = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(12_000)
                .followRedirects(false) // followed manually below — see class javadoc
                .execute();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not fetch URL: " + e.getMessage());
        }

        int status = response.statusCode();
        String location = response.header("Location");
        if (status >= 300 && status < 400 && location != null) {
            if (redirectsRemaining <= 0) {
                throw new IllegalArgumentException("Too many redirects");
            }
            return fetch(resolveRedirect(url, location), redirectsRemaining - 1);
        }

        try {
            return response.parse();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not parse the page: " + e.getMessage());
        }
    }

    private String resolveRedirect(String from, String location) {
        try {
            return new URI(from).resolve(location).toString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid redirect target");
        }
    }

    /** Scheme must be http(s), and every address the host resolves to (not just the
     *  first — DNS can return several) must be public. Doesn't mutate anything; this
     *  is purely a gate before fetch() is allowed to connect. */
    private void validateAndResolve(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException("Only http/https URLs are allowed");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Invalid URL");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Could not resolve host: " + host);
        }

        for (InetAddress address : addresses) {
            if (!isPubliclyRoutable(address)) {
                throw new IllegalArgumentException("That URL points at a private or internal address, which isn't allowed");
            }
        }
    }

    /** Loopback (127.0.0.0/8, ::1), link-local (169.254.0.0/16 — this is where cloud
     *  instance metadata endpoints live — and fe80::/10), site-local/RFC1918
     *  (10/8, 172.16/12, 192.168/16), multicast, and the wildcard address are all
     *  rejected. Covers the realistic SSRF target space via the JDK's own InetAddress
     *  predicates rather than hand-rolled CIDR math. */
    private boolean isPubliclyRoutable(InetAddress address) {
        return !(address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()
            || address.isAnyLocalAddress());
    }

    private ChurchDto tryJsonLd(Document doc) {
        for (Element script : doc.select("script[type='application/ld+json']")) {
            try {
                JsonNode root = mapper.readTree(script.html());
                // Unwrap @graph
                if (root.has("@graph")) root = root.get("@graph");

                if (root.isArray()) {
                    for (JsonNode node : root) {
                        ChurchDto result = parseNode(node);
                        if (result != null) return result;
                    }
                } else {
                    ChurchDto result = parseNode(root);
                    if (result != null) return result;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private ChurchDto parseNode(JsonNode node) {
        String type = node.path("@type").asText("");
        if (!type.contains("Church") && !type.contains("LocalBusiness") && !type.contains("Organization")) {
            return null;
        }

        String name = text(node, "name");
        String description = text(node, "description");
        String telephone = text(node, "telephone");
        String siteUrl = text(node, "url");

        String imageUrl = null;
        JsonNode img = node.path("image");
        if (!img.isMissingNode()) {
            imageUrl = img.isTextual() ? img.asText() : text(img, "url");
        }

        JsonNode addr = node.path("address");
        String street = text(addr, "streetAddress");
        String city = text(addr, "addressLocality");
        String state = text(addr, "addressRegion");
        String zip = text(addr, "postalCode");

        List<ServiceTimeDto> serviceTimes = new ArrayList<>();
        JsonNode hours = node.path("openingHoursSpecification");
        if (hours.isArray()) {
            for (JsonNode h : hours) {
                String opens = text(h, "opens");
                JsonNode days = h.path("dayOfWeek");
                if (days.isArray()) {
                    for (JsonNode day : days) {
                        String d = day.asText().replaceAll(".*/", "");
                        serviceTimes.add(new ServiceTimeDto(null, d, opens, "Service", null));
                    }
                } else if (!days.isMissingNode()) {
                    String d = days.asText().replaceAll(".*/", "");
                    serviceTimes.add(new ServiceTimeDto(null, d, opens, "Service", null));
                }
            }
        }

        if (name == null && description == null && telephone == null) return null;

        return new ChurchDto(null, name, null, street, city, state, zip,
            telephone, null, siteUrl, description, imageUrl, null,
            null, null, null, false, null, null, null, serviceTimes);
    }

    private ChurchDto fromMeta(Document doc) {
        String name = firstNonEmpty(
            doc.select("meta[property='og:site_name']").attr("content"),
            doc.select("meta[property='og:title']").attr("content"),
            doc.title()
        );
        String description = firstNonEmpty(
            doc.select("meta[name='description']").attr("content"),
            doc.select("meta[property='og:description']").attr("content")
        );
        String image = doc.select("meta[property='og:image']").attr("content");

        return new ChurchDto(null,
            name.isBlank() ? null : name, null,
            null, null, null, null, null, null, null,
            description.isBlank() ? null : description,
            image.isBlank() ? null : image,
            null, null, null, null, false, null, null, null, List.of());
    }

    private String text(JsonNode node, String field) {
        String val = node.path(field).asText(null);
        return (val == null || val.isBlank()) ? null : val;
    }

    private String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}
