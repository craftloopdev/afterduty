package com.afterduty.service.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Real eCFR client (Increment 7, spec §C.1). Plain {@link java.net.http.HttpClient},
 * no auth, base URL from {@code va-claim.kb.ecfr-base-url} (overridable so tests can
 * point at a fixture server). Polite: single-threaded use from the nightly job only;
 * no parallel hammering (rate limits unpublished).
 */
@Component
public class EcfrClientImpl implements EcfrClient {

    private static final Logger log = LoggerFactory.getLogger(EcfrClientImpl.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final String baseUrl;

    public EcfrClientImpl(@Value("${va-claim.kb.ecfr-base-url:https://www.ecfr.gov}") String baseUrl) {
        // Trim a trailing slash so path concatenation stays clean.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public TitleFreshness fetchTitleFreshness() {
        String body = get(baseUrl + "/api/versioner/v1/titles.json", Duration.ofSeconds(30));
        try {
            JsonNode root = objectMapper.readTree(body);
            for (JsonNode title : root.path("titles")) {
                if (title.path("number").asInt(-1) == 38) {
                    return new TitleFreshness(
                            parseDate(title.path("up_to_date_as_of").asText(null)),
                            parseDate(title.path("latest_amended_on").asText(null)));
                }
            }
            throw new EcfrException("title 38 not present in titles.json");
        } catch (EcfrException e) {
            throw e;
        } catch (Exception e) {
            throw new EcfrException("failed to parse titles.json: " + e.getMessage(), e);
        }
    }

    @Override
    public List<SectionVersion> fetchVersions(int part, LocalDate since) {
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/api/versioner/v1/versions/title-38.json?part=").append(part);
        if (since != null) {
            url.append("&issue_date%5Bgte%5D=")
                    .append(URLEncoder.encode(since.toString(), StandardCharsets.UTF_8));
        }
        String body = get(url.toString(), Duration.ofSeconds(30));
        try {
            JsonNode root = objectMapper.readTree(body);
            List<SectionVersion> out = new ArrayList<>();
            for (JsonNode v : root.path("content_versions")) {
                String id = v.path("identifier").asText(null);
                LocalDate issued = parseDate(v.path("issue_date").asText(null));
                if (id != null && issued != null) {
                    out.add(new SectionVersion(id, issued));
                }
            }
            return out;
        } catch (Exception e) {
            throw new EcfrException("failed to parse versions for part " + part + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String fetchPartXml(int part, LocalDate date) {
        String url = baseUrl + "/api/versioner/v1/full/" + date + "/title-38.xml?part=" + part;
        return get(url, Duration.ofSeconds(30));
    }

    private String get(String url, Duration timeout) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Accept", "application/json, application/xml")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new EcfrException("eCFR HTTP " + resp.statusCode() + " for " + url);
            }
            return resp.body();
        } catch (EcfrException e) {
            throw e;
        } catch (Exception e) {
            throw new EcfrException("eCFR request failed for " + url + ": " + e.getMessage(), e);
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank() || "null".equals(s)) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            log.debug("eCFR: unparseable date '{}'", s);
            return null;
        }
    }

    /** Network/parse failure of the eCFR feed. */
    public static class EcfrException extends RuntimeException {
        public EcfrException(String message) { super(message); }
        public EcfrException(String message, Throwable cause) { super(message, cause); }
    }
}
