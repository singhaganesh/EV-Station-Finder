package com.ganesh.finder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class StationImportService {

    private static final Logger log = LoggerFactory.getLogger(StationImportService.class);

    @Value("${ocm.api.key}")
    private String ocmApiKey;

    @Value("${ocm.sync.radius-km:500}")
    private int defaultRadiusKm;

    @Value("${ocm.sync.country-id:101}")
    private int countryId;

    private final StationPersistenceService persistenceService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public StationImportService(StationPersistenceService persistenceService,
                                RestTemplate externalRestTemplate) {
        this.persistenceService = persistenceService;
        this.restTemplate = externalRestTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Fetch stations from OCM within a radius of a center point.
     * NOT @Transactional: each station is persisted in its own transaction by
     * StationPersistenceService (REQUIRES_NEW), so one bad row cannot roll back the batch.
     */
    public int importFromOCM(double centerLat, double centerLng, int radiusKm) {
        return runImport(buildOCMUrl(centerLat, centerLng, radiusKm),
                "OCM stations near " + centerLat + "," + centerLng);
    }

    /**
     * Fetch stations from OCM for a specific country code.
     */
    public int importForCountry(String countryCode, int maxResults) {
        return runImport(buildOCMCountryUrl(countryCode, maxResults),
                "OCM stations for country " + countryCode);
    }

    private int runImport(String url, String description) {
        int imported = 0;
        int skipped = 0;
        try {
            log.info("Fetching {} from: {}", description, url);

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "station-finder-backend/1.0");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = exchangeWithRetry(url, entity, description);
            if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("OCM API call failed after retries ({})", description);
                return 0;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            if (!root.isArray()) {
                log.error("OCM response is not an array ({})", description);
                return 0;
            }

            for (JsonNode node : root) {
                try {
                    if (persistenceService.transformAndSave(node)) {
                        imported++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    // Per-station rollback already happened (REQUIRES_NEW); just record and continue.
                    log.warn("Failed to import OCM station: {}", e.getMessage());
                    skipped++;
                }
            }

            log.info("OCM import complete ({}): {} imported, {} skipped (total {})",
                    description, imported, skipped, root.size());
        } catch (Exception e) {
            log.error("OCM import failed ({}): {}", description, e.getMessage(), e);
        }
        return imported;
    }

    /**
     * Call OCM with up to 3 attempts and exponential backoff. Retries on 429 (rate
     * limit) and 5xx; a successful or 4xx-other response returns immediately so the
     * daily sync degrades gracefully instead of silently importing nothing.
     */
    private ResponseEntity<String> exchangeWithRetry(String url, HttpEntity<String> entity, String description) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                int status = response.getStatusCode().value();
                boolean retryable = status == 429 || (status >= 500 && status < 600);
                if (!retryable) {
                    return response;
                }
                log.warn("OCM returned retryable status {} (attempt {}/{}, {})", status, attempt, maxAttempts, description);
            } catch (Exception e) {
                log.warn("OCM call error (attempt {}/{}, {}): {}", attempt, maxAttempts, description, e.getMessage());
            }
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(1000L * attempt); // 1s, then 2s
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return null;
    }

    private String buildOCMUrl(double lat, double lng, int radiusKm) {
        String baseUrl = "https://api.openchargemap.io/v3/poi";
        return baseUrl + "?key=" + URLEncoder.encode(ocmApiKey, StandardCharsets.UTF_8)
                + "&latitude=" + lat
                + "&longitude=" + lng
                + "&distance=" + radiusKm
                + "&distanceunit=KM"
                + "&maxresults=100"
                + "&compact=true"
                + "&verbose=false";
    }

    private String buildOCMCountryUrl(String countryCode, int maxResults) {
        String baseUrl = "https://api.openchargemap.io/v3/poi";
        return baseUrl + "?key=" + URLEncoder.encode(ocmApiKey, StandardCharsets.UTF_8)
                + "&countrycode=" + countryCode
                + "&maxresults=" + maxResults
                + "&compact=true"
                + "&verbose=false";
    }
}
