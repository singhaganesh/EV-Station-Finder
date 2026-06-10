package com.ganesh.finder.service;

import com.ganesh.finder.model.ChargerSlot;
import com.ganesh.finder.model.Station;
import com.ganesh.finder.repository.ChargerSlotRepository;
import com.ganesh.finder.repository.StationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists a single OCM station in its OWN transaction.
 *
 * This lives in a separate bean (not StationImportService) on purpose: a
 * {@code @Transactional} method called via {@code this.method()} is NOT
 * intercepted by Spring's proxy, which silently disables per-station rollback.
 * By calling this through an injected bean, REQUIRES_NEW takes effect and one
 * bad station rolls back only itself.
 */
@Service
public class StationPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(StationPersistenceService.class);

    private final StationRepository stationRepository;
    private final ChargerSlotRepository chargerSlotRepository;
    private final ObjectMapper objectMapper;

    public StationPersistenceService(StationRepository stationRepository,
                                     ChargerSlotRepository chargerSlotRepository) {
        this.stationRepository = stationRepository;
        this.chargerSlotRepository = chargerSlotRepository;
        this.objectMapper = new ObjectMapper();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean transformAndSave(JsonNode ocmNode) {
        long ocmId = ocmNode.has("ID") && !ocmNode.get("ID").isNull()
                ? ocmNode.get("ID").asLong()
                : -1;

        if (ocmId == -1) {
            return false; // Skip stations without ID
        }

        Optional<Station> existing = stationRepository.findByOcmId(ocmId);
        if (existing.isPresent()) {
            Station station = existing.get();
            station.setLastSynced(LocalDateTime.now());
            stationRepository.save(station);
            return false; // Already exists
        }

        JsonNode addressInfo = ocmNode.get("AddressInfo");
        if (addressInfo == null) {
            return false;
        }

        String name = textOrDefault(addressInfo, "Title", "Unknown Station");
        double latitude = addressInfo.has("Latitude") && !addressInfo.get("Latitude").isNull()
                ? addressInfo.get("Latitude").asDouble() : 0;
        double longitude = addressInfo.has("Longitude") && !addressInfo.get("Longitude").isNull()
                ? addressInfo.get("Longitude").asDouble() : 0;

        if (latitude == 0 && longitude == 0) {
            return false; // Skip stations with no coordinates
        }

        // Build address string
        StringBuilder addressBuilder = new StringBuilder();
        appendIfPresent(addressBuilder, addressInfo, "AddressLine1");
        appendIfPresent(addressBuilder, addressInfo, "Town");
        appendIfPresent(addressBuilder, addressInfo, "StateOrProvince");
        String address = addressBuilder.length() > 0 ? addressBuilder.toString() : "Address not available";

        String ocmUuid = textOrNull(ocmNode, "UUID");

        // Build meta with Jackson (no hand-rolled JSON / escaping).
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("ocm_id", ocmId);
        if (ocmUuid != null) {
            meta.put("ocm_uuid", ocmUuid);
        }
        if (ocmNode.has("OperatorInfo") && !ocmNode.get("OperatorInfo").isNull()) {
            JsonNode operator = ocmNode.get("OperatorInfo");
            String opTitle = textOrNull(operator, "Title");
            if (opTitle != null) meta.put("ocm_operator", opTitle);
            String website = textOrNull(operator, "WebsiteURL");
            if (website != null) meta.put("ocm_website", website);
        }
        if (ocmNode.has("UsageType") && !ocmNode.get("UsageType").isNull()) {
            String usageTitle = textOrNull(ocmNode.get("UsageType"), "Title");
            if (usageTitle != null) meta.put("ocm_usage_type", usageTitle);
        }
        String comments = textOrNull(ocmNode, "GeneralComments");
        if (comments != null) {
            meta.put("ocm_comments", comments);
        }
        meta.put("source", "OCM");
        meta.put("last_synced", LocalDateTime.now().toString());

        String metaJson;
        try {
            metaJson = objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            log.warn("Failed to serialize meta for station {}: {}", name, e.getMessage());
            metaJson = "{}";
        }

        // Operating hours from usage type
        String operatingHours = "24 Hours";
        if (ocmNode.has("UsageType") && !ocmNode.get("UsageType").isNull()) {
            String usageTitle = textOrDefault(ocmNode.get("UsageType"), "Title", "").toLowerCase();
            if (!usageTitle.contains("pay") && !usageTitle.contains("public")) {
                operatingHours = "Check with operator";
            }
        }

        Station station = Station.builder()
                .name(name)
                .latitude(latitude)
                .longitude(longitude)
                .address(address)
                .operatingHours(operatingHours)
                .pricePerKwh(0.0)
                .rating(0.0)
                .isOpen(true)
                .ocmId(ocmId)
                .ocmUuid(ocmUuid)
                .meta(metaJson)
                .lastSynced(LocalDateTime.now())
                .build();

        station = stationRepository.save(station);

        List<ChargerSlot> slotsToCreate = new ArrayList<>();
        if (ocmNode.has("Connections") && ocmNode.get("Connections").isArray()) {
            for (JsonNode conn : ocmNode.get("Connections")) {
                int quantity = conn.has("Quantity") && !conn.get("Quantity").isNull()
                        ? conn.get("Quantity").asInt() : 1;
                double powerKw = conn.has("PowerKW") && !conn.get("PowerKW").isNull()
                        ? conn.get("PowerKW").asDouble() : 22.0;
                String connectorType = mapConnectorType(conn);
                String slotPrefix = connectorType.replaceAll("[^a-zA-Z0-9]", "");
                for (int i = 0; i < quantity; i++) {
                    slotsToCreate.add(ChargerSlot.builder()
                            .station(station)
                            .slotLabel(slotPrefix + " #" + (i + 1))
                            .connectorType(connectorType)
                            .powerKw(powerKw)
                            .isAvailable(true)
                            .build());
                }
            }
        }

        if (slotsToCreate.isEmpty()) {
            for (int i = 0; i < 2; i++) {
                slotsToCreate.add(ChargerSlot.builder()
                        .station(station)
                        .slotLabel("CCS2 #" + (i + 1))
                        .connectorType("CCS2")
                        .powerKw(60.0)
                        .isAvailable(true)
                        .build());
            }
        }

        chargerSlotRepository.saveAll(slotsToCreate);
        log.info("Imported station: {} ({} slots)", name, slotsToCreate.size());
        return true;
    }

    private String mapConnectorType(JsonNode connection) {
        if (connection.has("ConnectionType") && !connection.get("ConnectionType").isNull()) {
            JsonNode connType = connection.get("ConnectionType");
            String title = connType.has("Title") && !connType.get("Title").isNull()
                    ? connType.get("Title").asText().toLowerCase() : "";
            if (title.contains("ccs") || title.contains("combo")) return "CCS2";
            if (title.contains("type 2") || title.contains("type2") || title.contains("mennekes")) return "Type 2";
            if (title.contains("chademo")) return "CHAdeMO";
            if (title.contains("type 1") || title.contains("type1") || title.contains("j1772")) return "Type 1";
        }
        if (connection.has("PowerKW") && !connection.get("PowerKW").isNull()) {
            double power = connection.get("PowerKW").asDouble();
            return power > 22 ? "CCS2" : "Type 2";
        }
        return "CCS2";
    }

    private static void appendIfPresent(StringBuilder sb, JsonNode node, String field) {
        String value = textOrNull(node, field);
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(value);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        return node != null && node.has(field) && !node.get(field).isNull()
                ? node.get(field).asText() : null;
    }

    private static String textOrDefault(JsonNode node, String field, String def) {
        String v = textOrNull(node, field);
        return v != null ? v : def;
    }
}
