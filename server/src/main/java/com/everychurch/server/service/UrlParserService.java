package com.everychurch.server.service;

import com.everychurch.server.dto.ChurchDto;
import com.everychurch.server.dto.ServiceTimeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class UrlParserService {

    private final ObjectMapper mapper = new ObjectMapper();

    public ChurchDto parse(String url) {
        Document doc;
        try {
            doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(12_000)
                .followRedirects(true)
                .get();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not fetch URL: " + e.getMessage());
        }

        ChurchDto fromLd = tryJsonLd(doc);
        return fromLd != null ? fromLd : fromMeta(doc);
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
            null, null, null, false, serviceTimes);
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
            null, null, null, null, false, List.of());
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
