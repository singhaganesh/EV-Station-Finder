package com.ganesh.finder.service;

import com.ganesh.finder.dto.StationMarker;
import com.ganesh.finder.dto.StationWithScore;
import com.ganesh.finder.model.ChargerSlot;
import com.ganesh.finder.model.Station;
import com.ganesh.finder.model.Review;
import com.ganesh.finder.model.AppUser;
import com.ganesh.finder.repository.ChargerSlotRepository;
import com.ganesh.finder.repository.StationRepository;
import com.ganesh.finder.repository.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.RedisTemplate;
import com.ganesh.finder.dto.RoutePlanResponse;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import java.util.Map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class StationService {

    private static final Logger log = LoggerFactory.getLogger(StationService.class);

    private final StationRepository stationRepository;
    private final ChargerSlotRepository chargerSlotRepository;
    private final ReviewRepository reviewRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    public StationService(StationRepository stationRepository,
                          ChargerSlotRepository chargerSlotRepository,
                          ReviewRepository reviewRepository,
                          RedisTemplate<String, Object> redisTemplate) {
        this.stationRepository = stationRepository;
        this.chargerSlotRepository = chargerSlotRepository;
        this.reviewRepository = reviewRepository;
        this.redisTemplate = redisTemplate;
    }

    public record ViewportResponse(List<StationMarker> markers, boolean tooMany) {}

    /**
     * Get lightweight markers for stations in a viewport.
     */
    public List<StationMarker> getStationsInViewport(double neLat, double neLng,
                                                      double swLat, double swLng) {
        List<Station> stations = stationRepository.findStationsInViewport(
                Math.min(swLat, neLat), Math.max(swLat, neLat),
                Math.min(swLng, neLng), Math.max(swLng, neLng));

        return stations.stream()
                .filter(distinctByKey(s -> s.getOcmId() != null ? "ocm_" + s.getOcmId() : s.getLatitude() + "," + s.getLongitude()))
                .map(station -> {
                    long availableSlots = chargerSlotRepository.countByStationIdAndIsAvailableTrue(station.getId());
                    return StationMarker.builder()
                            .id(station.getId())
                            .name(station.getName())
                            .latitude(station.getLatitude())
                            .longitude(station.getLongitude())
                            .available(availableSlots > 0)
                            .build();
                }).collect(Collectors.toList());
    }

    /**
     * Get optimized lightweight markers for stations in a viewport, including rating, distance,
     * available slots, total slots, and connector types.
     */
    public ViewportResponse getStationsInViewportOptimized(
            double neLat, double neLng, double swLat, double swLng, String connectorType) {

        String cacheKey = String.format("viewport:%.3f:%.3f:%.3f:%.3f:%s",
                Math.round(neLat * 1000.0) / 1000.0,
                Math.round(neLng * 1000.0) / 1000.0,
                Math.round(swLat * 1000.0) / 1000.0,
                Math.round(swLng * 1000.0) / 1000.0,
                connectorType != null ? connectorType : "ALL");

        try {
            ViewportResponse cached = (ViewportResponse) redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.info("Redis Cache HIT for key: {}", cacheKey);
                return cached;
            }
        } catch (Exception e) {
            log.error("Redis read error", e);
        }

        double minLat = Math.min(swLat, neLat), maxLat = Math.max(swLat, neLat);
        double minLng = Math.min(swLng, neLng), maxLng = Math.max(swLng, neLng);

        long totalCount;
        List<Station> stations;

        if (connectorType != null && !connectorType.trim().isEmpty() && !connectorType.equalsIgnoreCase("All")) {
            totalCount = stationRepository.countStationsInViewportAndConnector(minLat, maxLat, minLng, maxLng, connectorType);
            stations = stationRepository.findStationsInViewportWithSlotsAndConnector(minLat, maxLat, minLng, maxLng, connectorType);
        } else {
            totalCount = stationRepository.countStationsInViewport(minLat, maxLat, minLng, maxLng);
            stations = stationRepository.findStationsInViewportWithSlots(minLat, maxLat, minLng, maxLng);
        }

        boolean tooMany = totalCount > 200;


        double centerLat = (minLat + maxLat) / 2.0;
        double centerLng = (minLng + maxLng) / 2.0;

        List<StationMarker> markers = stations.stream()
            .filter(distinctByKey(s -> s.getOcmId() != null ? "ocm_" + s.getOcmId() : s.getLatitude() + "," + s.getLongitude()))
            .map(station -> {
                List<ChargerSlot> slots = station.getChargerSlots();
                if (slots == null) {
                    slots = java.util.Collections.emptyList();
                }
                long availableCount = slots.stream().filter(ChargerSlot::getIsAvailable).count();
                List<String> connectorTypes = slots.stream()
                    .map(ChargerSlot::getConnectorType)
                    .filter(c -> c != null)
                    .distinct()
                    .collect(Collectors.toList());
                double dist = calculateDistance(centerLat, centerLng,
                        station.getLatitude(), station.getLongitude());

                return StationMarker.builder()
                    .id(station.getId())
                    .name(station.getName())
                    .latitude(station.getLatitude())
                    .longitude(station.getLongitude())
                    .available(availableCount > 0)
                    .rating(station.getRating())
                    .distance(dist)
                    .availableSlots((int) availableCount)
                    .totalSlots(slots.size())
                    .connectorTypes(connectorTypes)
                    .build();
            })
            .sorted(Comparator.comparingDouble(StationMarker::getDistance))
            .limit(200)
            .collect(Collectors.toList());

        ViewportResponse response = new ViewportResponse(markers, tooMany);

        try {
            redisTemplate.opsForValue().set(cacheKey, response, java.time.Duration.ofHours(1));
            log.info("Redis Cache MISS. Saved key: {}", cacheKey);
        } catch (Exception e) {
            log.error("Redis write error", e);
        }

        return response;
    }

    /**
     * Get nearby stations ranked by distance.
     */
    public List<StationWithScore> getNearbyStations(double lat, double lng,
                                                     double radiusKm, int limit) {
        // Calculate approximate bounding box
        double latDelta = radiusKm / 111.0;
        double lngDelta = radiusKm / (111.0 * Math.cos(Math.toRadians(lat)));

        List<Station> stations = stationRepository.findStationsInViewport(
                lat - latDelta, lat + latDelta,
                lng - lngDelta, lng + lngDelta);

        return stations.stream()
                .filter(distinctByKey(s -> s.getOcmId() != null ? "ocm_" + s.getOcmId() : s.getLatitude() + "," + s.getLongitude()))
                .map(station -> enrichWithScore(station, lat, lng))
                .filter(s -> s.getDistance() <= radiusKm)
                .sorted(Comparator.comparingDouble(StationWithScore::getDistance))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Get detailed info for a single station.
     */
    public Optional<StationWithScore> getStationDetail(Long id, double userLat, double userLng) {
        return stationRepository.findById(id)
                .map(station -> enrichWithScore(station, userLat, userLng));
    }

    /**
     * Search stations by name or address.
     */
    public List<StationWithScore> searchStations(String query, double lat, double lng, double radiusKm) {
        List<Station> stations = stationRepository.searchByNameOrAddress(query.toLowerCase().trim());

        return stations.stream()
                .filter(distinctByKey(s -> s.getOcmId() != null ? "ocm_" + s.getOcmId() : s.getLatitude() + "," + s.getLongitude()))
                .map(station -> enrichWithScore(station, lat, lng))
                .filter(s -> s.getDistance() <= radiusKm)
                .sorted(Comparator.comparingDouble(StationWithScore::getDistance))
                .collect(Collectors.toList());
    }

    /**
     * Get total station count.
     */
    public long getStationCount() {
        return stationRepository.count();
    }

    /**
     * Enrich a Station entity with scoring and slot data.
     */
    private StationWithScore enrichWithScore(Station station, double distance, List<ChargerSlot> slots) {
        if (slots == null) {
            slots = new ArrayList<>();
        }
        long availableCount = slots.stream().filter(ChargerSlot::getIsAvailable).count();

        // Extract unique connector types
        List<String> connectorTypes = slots.stream()
                .map(ChargerSlot::getConnectorType)
                .distinct()
                .collect(Collectors.toList());

        // Slot details
        List<StationWithScore.SlotInfo> slotInfos = slots.stream()
                .map(slot -> StationWithScore.SlotInfo.builder()
                        .id(slot.getId())
                        .label(slot.getSlotLabel())
                        .connectorType(slot.getConnectorType())
                        .powerKw(slot.getPowerKw())
                        .isAvailable(slot.getIsAvailable())
                        .build())
                .collect(Collectors.toList());

        return StationWithScore.builder()
                .id(station.getId())
                .name(station.getName())
                .latitude(station.getLatitude())
                .longitude(station.getLongitude())
                .address(station.getAddress())
                .operatingHours(station.getOperatingHours())
                .pricePerKwh(station.getPricePerKwh())
                .rating(station.getRating())
                .isOpen(station.getIsOpen())
                .meta(station.getMeta())
                .distance(distance)
                .totalSlots(slots.size())
                .availableSlots((int) availableCount)
                .connectorTypes(connectorTypes)
                .slots(slotInfos)
                .build();
    }

    private StationWithScore enrichWithScore(Station station, double userLat, double userLng) {
        double distance = calculateDistance(userLat, userLng, station.getLatitude(), station.getLongitude());
        List<ChargerSlot> slots = chargerSlotRepository.findByStationId(station.getId());
        return enrichWithScore(station, distance, slots);
    }

    /**
     * Haversine distance between two lat/lng points in kilometers.
     */
    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371; // Earth's radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /**
     * Get reviews for a station.
     */
    public List<Review> getReviewsForStation(Long stationId) {
        return reviewRepository.findByStationIdOrderByCreatedAtDesc(stationId);
    }

    /**
     * Add a review for a station and recalculate its average rating.
     */
    @org.springframework.transaction.annotation.Transactional
    public Review addReviewForStation(Long stationId, AppUser user, double rating, String comment) {
        if (reviewRepository.existsByStationIdAndUserId(stationId, user.getId())) {
            throw new IllegalStateException("You have already reviewed this station.");
        }

        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new IllegalArgumentException("Station not found with id: " + stationId));

        Review review = Review.builder()
                .station(station)
                .user(user)
                .reviewerName(user.getDisplayName() != null ? user.getDisplayName() : "Anonymous")
                .rating(rating)
                .comment(comment)
                .build();

        review = reviewRepository.save(review);

        // Recalculate average rating
        Double avgRating = reviewRepository.getAverageRatingForStation(stationId);
        if (avgRating != null) {
            // Round to 1 decimal place
            double roundedRating = Math.round(avgRating * 10.0) / 10.0;
            station.setRating(roundedRating);
            stationRepository.save(station);
            log.info("Updated station {} average rating to {}", stationId, roundedRating);
        }

        return review;
    }

    /**
     * Get stations along a route.
     */
    public List<StationWithScore> getStationsAlongRoute(String waypointsStr, String connectorType, double bufferKm) {
        List<double[]> waypoints = new ArrayList<>();
        if (waypointsStr.contains("|")) {
            String[] parts = waypointsStr.split("\\|");
            for (String part : parts) {
                String[] coords = part.split(",");
                if (coords.length >= 2) {
                    try {
                        double lat = Double.parseDouble(coords[0]);
                        double lng = Double.parseDouble(coords[1]);
                        waypoints.add(new double[]{lat, lng});
                    } catch (NumberFormatException ignored) {}
                }
            }
        } else {
            waypoints = decodePolyline(waypointsStr);
        }

        if (waypoints.isEmpty()) {
            return new ArrayList<>();
        }

        // Compute cumulative distance along the waypoints
        double totalDistance = 0.0;
        double[] distAlongRoute = new double[waypoints.size()];
        distAlongRoute[0] = 0.0;
        for (int i = 1; i < waypoints.size(); i++) {
            double stepDist = calculateDistance(waypoints.get(i - 1)[0], waypoints.get(i - 1)[1], waypoints.get(i)[0], waypoints.get(i)[1]);
            totalDistance += stepDist;
            distAlongRoute[i] = totalDistance;
        }

        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;
        for (double[] pt : waypoints) {
            minLat = Math.min(minLat, pt[0]);
            maxLat = Math.max(maxLat, pt[0]);
            minLng = Math.min(minLng, pt[1]);
            maxLng = Math.max(maxLng, pt[1]);
        }

        // Expand bounding box by bufferKm
        double latDelta = bufferKm / 111.0;
        double lngDelta = bufferKm / (111.0 * Math.cos(Math.toRadians((minLat + maxLat) / 2.0)));

        List<Station> stations = stationRepository.findStationsInViewport(
                minLat - latDelta, maxLat + latDelta,
                minLng - lngDelta, maxLng + lngDelta);

        List<StationWithScore> candidates = new ArrayList<>();
        Map<Long, Double> stationProgress = new java.util.HashMap<>();

        List<Station> dedupedStations = stations.stream()
                .filter(distinctByKey(s -> s.getOcmId() != null ? "ocm_" + s.getOcmId() : s.getLatitude() + "," + s.getLongitude()))
                .collect(Collectors.toList());

        List<Station> candidateStations = new ArrayList<>();
        Map<Long, Double> stationMinDistances = new java.util.HashMap<>();

        for (Station station : dedupedStations) {
            double minDistance = Double.MAX_VALUE;
            int closestWpIdx = 0;

            for (int i = 0; i < waypoints.size() - 1; i++) {
                double[] pA = waypoints.get(i);
                double[] pB = waypoints.get(i + 1);
                double dist = distanceToSegment(station.getLatitude(), station.getLongitude(), pA[0], pA[1], pB[0], pB[1]);
                if (dist < minDistance) {
                    minDistance = dist;
                    closestWpIdx = i;
                }
            }

            if (waypoints.size() == 1) {
                double[] pt = waypoints.get(0);
                minDistance = calculateDistance(station.getLatitude(), station.getLongitude(), pt[0], pt[1]);
                closestWpIdx = 0;
            }

            if (minDistance <= bufferKm) {
                candidateStations.add(station);
                stationMinDistances.put(station.getId(), minDistance);
                double progress = distAlongRoute[closestWpIdx];
                stationProgress.put(station.getId(), progress);
            }
        }

        // Batch query all slots for candidate stations
        List<Long> stationIds = candidateStations.stream().map(Station::getId).collect(Collectors.toList());
        Map<Long, List<ChargerSlot>> slotsByStationId = new java.util.HashMap<>();
        if (!stationIds.isEmpty()) {
            List<ChargerSlot> allSlots = chargerSlotRepository.findByStationIdIn(stationIds);
            slotsByStationId = allSlots.stream().collect(Collectors.groupingBy(slot -> slot.getStation().getId()));
        }

        // Construct enriched results in memory
        for (Station station : candidateStations) {
            double minDistance = stationMinDistances.get(station.getId());
            List<ChargerSlot> slots = slotsByStationId.getOrDefault(station.getId(), new ArrayList<>());
            StationWithScore enriched = enrichWithScore(station, 0.0, slots);
            enriched.setDistance(Math.round(minDistance * 100.0) / 100.0);

            if (connectorType == null || connectorType.trim().isEmpty() ||
                    (enriched.getConnectorTypes() != null && enriched.getConnectorTypes().stream().anyMatch(c -> c.equalsIgnoreCase(connectorType.trim())))) {
                candidates.add(enriched);
            }
        }

        // Sort candidates chronologically (from start to end of route)
        candidates.sort(Comparator.comparingDouble(s -> stationProgress.getOrDefault(s.getId(), 0.0)));

        // Smart Filtering Algorithm
        List<StationWithScore> selected = new ArrayList<>();
        if (totalDistance < 250.0) {
            // Short route: select up to 2 best stations near the midpoint
            double midMin = totalDistance * 0.25;
            double midMax = totalDistance * 0.75;
            List<StationWithScore> midCandidates = new ArrayList<>();
            for (StationWithScore s : candidates) {
                double progress = stationProgress.getOrDefault(s.getId(), 0.0);
                if (progress >= midMin && progress <= midMax) {
                    midCandidates.add(s);
                }
            }
            // Sort by availability and rating
            midCandidates.sort((c1, c2) -> {
                boolean av1 = (c1.getAvailableSlots() != null && c1.getAvailableSlots() > 0);
                boolean av2 = (c2.getAvailableSlots() != null && c2.getAvailableSlots() > 0);
                if (av1 != av2) return av1 ? -1 : 1;
                double rating1 = c1.getRating() != null ? c1.getRating() : 0.0;
                double rating2 = c2.getRating() != null ? c2.getRating() : 0.0;
                return Double.compare(rating2, rating1);
            });
            for (int k = 0; k < Math.min(2, midCandidates.size()); k++) {
                selected.add(midCandidates.get(k));
            }
        } else {
            // Long route: spaced-out window selection (target 200km intervals)
            double lastProgress = 0.0;
            double targetInterval = 200.0;

            while (lastProgress + 80.0 < totalDistance) {
                double windowMin = lastProgress + 140.0;
                double windowMax = lastProgress + 260.0;

                List<StationWithScore> windowCandidates = new ArrayList<>();
                for (StationWithScore s : candidates) {
                    double progress = stationProgress.getOrDefault(s.getId(), 0.0);
                    if (progress >= windowMin && progress <= windowMax) {
                        windowCandidates.add(s);
                    }
                }

                if (windowCandidates.isEmpty()) {
                    // Expand search window
                    windowMin = lastProgress + 80.0;
                    windowMax = lastProgress + 300.0;
                    for (StationWithScore s : candidates) {
                        double progress = stationProgress.getOrDefault(s.getId(), 0.0);
                        if (progress >= windowMin && progress <= windowMax) {
                            windowCandidates.add(s);
                        }
                    }
                }

                if (windowCandidates.isEmpty()) {
                    // Find the single closest station ahead
                    StationWithScore bestMatch = null;
                    double bestDiff = Double.MAX_VALUE;
                    for (StationWithScore s : candidates) {
                        double progress = stationProgress.getOrDefault(s.getId(), 0.0);
                        if (progress > lastProgress + 30.0 && progress < totalDistance - 30.0) {
                            double diff = Math.abs(progress - (lastProgress + targetInterval));
                            if (diff < bestDiff) {
                                bestDiff = diff;
                                bestMatch = s;
                            }
                        }
                    }
                    if (bestMatch != null) {
                        windowCandidates.add(bestMatch);
                    }
                }

                if (windowCandidates.isEmpty()) {
                    break; // No candidates found ahead
                }

                // Sort by availability and rating
                windowCandidates.sort((c1, c2) -> {
                    boolean av1 = (c1.getAvailableSlots() != null && c1.getAvailableSlots() > 0);
                    boolean av2 = (c2.getAvailableSlots() != null && c2.getAvailableSlots() > 0);
                    if (av1 != av2) return av1 ? -1 : 1;
                    double rating1 = c1.getRating() != null ? c1.getRating() : 0.0;
                    double rating2 = c2.getRating() != null ? c2.getRating() : 0.0;
                    return Double.compare(rating2, rating1);
                });

                StationWithScore chosen = windowCandidates.get(0);
                selected.add(chosen);
                lastProgress = stationProgress.getOrDefault(chosen.getId(), lastProgress + targetInterval);
            }
        }

        // Sort final selected list chronologically
        selected.sort(Comparator.comparingDouble(s -> stationProgress.getOrDefault(s.getId(), 0.0)));

        // Fallback: if no stations were selected by the filter, return first 6 chronological candidates
        if (selected.isEmpty() && !candidates.isEmpty()) {
            for (int k = 0; k < Math.min(6, candidates.size()); k++) {
                selected.add(candidates.get(k));
            }
        }

        return selected;
    }

    private double distanceToSegment(double latS, double lngS, double latA, double lngA, double latB, double lngB) {
        double xS = lngS, yS = latS;
        double xA = lngA, yA = latA;
        double xB = lngB, yB = latB;

        double dx = xB - xA;
        double dy = yB - yA;

        if (dx == 0 && dy == 0) {
            return calculateDistance(latS, lngS, latA, lngA);
        }

        double t = ((xS - xA) * dx + (yS - yA) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t)); // clamp to segment

        double projLat = latA + t * (latB - latA);
        double projLng = lngA + t * (lngB - lngA);

        return calculateDistance(latS, lngS, projLat, projLng);
    }

    private List<double[]> decodePolyline(String encoded) {
        List<double[]> poly = new ArrayList<>();
        int index = 0, len = encoded.length();
        int lat = 0, lng = 0;
        while (index < len) {
            int b, shift = 0, result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlat = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lat += dlat;
            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlng = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lng += dlng;
            double latitude = lat / 1E5;
            double longitude = lng / 1E5;
            poly.add(new double[]{latitude, longitude});
        }
        return poly;
    }

    private static <T> java.util.function.Predicate<T> distinctByKey(java.util.function.Function<? super T, ?> keyExtractor) {
        java.util.Set<Object> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        return t -> seen.add(keyExtractor.apply(t));
    }

    @org.springframework.transaction.annotation.Transactional
    public int removeDuplicateStations() {
        List<Station> allStations = stationRepository.findAll();
        List<Long> duplicateStationIds = new ArrayList<>();

        // Group 1: By ocmId (only for non-null ocmId)
        java.util.Map<Long, List<Station>> ocmGroups = allStations.stream()
                .filter(s -> s.getOcmId() != null)
                .collect(Collectors.groupingBy(Station::getOcmId));

        for (java.util.Map.Entry<Long, List<Station>> entry : ocmGroups.entrySet()) {
            List<Station> group = entry.getValue();
            if (group.size() > 1) {
                group.sort(Comparator.comparing(Station::getId));
                for (int i = 1; i < group.size(); i++) {
                    duplicateStationIds.add(group.get(i).getId());
                }
            }
        }

        // Group 2: By coordinates and name (for mock stations where ocmId is null)
        java.util.Map<String, List<Station>> coordGroups = allStations.stream()
                .filter(s -> !duplicateStationIds.contains(s.getId()))
                .collect(Collectors.groupingBy(s -> {
                    double lat = Math.round(s.getLatitude() * 100000.0) / 100000.0;
                    double lng = Math.round(s.getLongitude() * 100000.0) / 100000.0;
                    return s.getName().trim().toLowerCase() + "_" + lat + "_" + lng;
                }));

        for (java.util.Map.Entry<String, List<Station>> entry : coordGroups.entrySet()) {
            List<Station> group = entry.getValue();
            if (group.size() > 1) {
                group.sort(Comparator.comparing(Station::getId));
                for (int i = 1; i < group.size(); i++) {
                    duplicateStationIds.add(group.get(i).getId());
                }
            }
        }

        if (!duplicateStationIds.isEmpty()) {
            log.info("Found {} duplicate stations to delete.", duplicateStationIds.size());
            reviewRepository.deleteByStationIdIn(duplicateStationIds);
            chargerSlotRepository.deleteByStationIdIn(duplicateStationIds);
            stationRepository.deleteAllById(duplicateStationIds);
            log.info("Successfully deleted duplicate stations.");
        } else {
            log.info("No duplicate stations found.");
        }

        return duplicateStationIds.size();
    }

    public RoutePlanResponse planRoute(String fromAddress, String toAddress, String connectorType) {
        String connKey = (connectorType != null) ? connectorType.trim().toLowerCase() : "any";
        String cacheKey = String.format("route:%s:%s:%s", 
                fromAddress.trim().toLowerCase(), 
                toAddress.trim().toLowerCase(), 
                connKey);
        
        try {
            if (redisTemplate != null) {
                RoutePlanResponse cachedResponse = (RoutePlanResponse) redisTemplate.opsForValue().get(cacheKey);
                if (cachedResponse != null) {
                    log.info("Route CACHE HIT for: {} -> {} (connector: {})", fromAddress, toAddress, connKey);
                    return cachedResponse;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read from Redis route cache for: '{}' to '{}' - error: {}", fromAddress, toAddress, e.getMessage());
        }

        String[] fromName = new String[]{fromAddress};
        String[] toName = new String[]{toAddress};

        // Parallel geocoding
        java.util.concurrent.CompletableFuture<double[]> startFuture = 
                java.util.concurrent.CompletableFuture.supplyAsync(() -> geocodeAddress(fromAddress, fromName));
        java.util.concurrent.CompletableFuture<double[]> endFuture = 
                java.util.concurrent.CompletableFuture.supplyAsync(() -> geocodeAddress(toAddress, toName));

        double[] start;
        double[] end;
        try {
            start = startFuture.get();
            end = endFuture.get();
        } catch (Exception e) {
            log.error("Parallel geocoding failed", e);
            throw new RuntimeException("Geocoding failed due to internal query execution error.", e);
        }

        if (start == null && end == null) {
            throw new IllegalArgumentException("Could not resolve starting address '" + fromAddress + "' and ending address '" + toAddress + "'.");
        } else if (start == null) {
            throw new IllegalArgumentException("Could not resolve starting address coordinates for: '" + fromAddress + "'.");
        } else if (end == null) {
            throw new IllegalArgumentException("Could not resolve ending address coordinates for: '" + toAddress + "'.");
        }

        double[] distanceKmObj = new double[]{0.0};
        double[] durationSecObj = new double[]{0.0};

        List<double[]> routePoints = fetchRouteFromOSRM(start, end, distanceKmObj, durationSecObj);

        // Downsample the path points to ~30 points to keep corridor search fast
        List<double[]> simplifiedPoints = new ArrayList<>();
        int step = Math.max(1, routePoints.size() / 30);
        for (int i = 0; i < routePoints.size(); i++) {
            if (i == 0 || i == routePoints.size() - 1 || i % step == 0) {
                simplifiedPoints.add(routePoints.get(i));
            }
        }

        // Format waypoints query for our corridor search
        String waypointsStr = simplifiedPoints.stream()
                .map(pt -> String.format(java.util.Locale.US, "%f,%f", pt[0], pt[1]))
                .collect(Collectors.joining("|"));

        List<StationWithScore> stations = getStationsAlongRoute(waypointsStr, connectorType, 10.0);

        RoutePlanResponse response = RoutePlanResponse.builder()
                .fromName(fromName[0])
                .toName(toName[0])
                .distanceKm(Math.round(distanceKmObj[0] * 10.0) / 10.0)
                .durationSec(durationSecObj[0])
                .routePoints(routePoints)
                .stations(stations)
                .build();

        try {
            if (redisTemplate != null) {
                redisTemplate.opsForValue().set(cacheKey, response, 1, java.util.concurrent.TimeUnit.HOURS);
                log.info("Cached route plan in Redis for key: {}", cacheKey);
            }
        } catch (Exception e) {
            log.warn("Failed to write to Redis route cache for key: {} - error: {}", cacheKey, e.getMessage());
        }

        return response;
    }

    private double[] geocodeAddress(String address, String[] outName) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }
        String cacheKey = "geocode:" + address.trim().toLowerCase();
        try {
            if (redisTemplate != null) {
                Map<String, Object> cached = (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);
                if (cached != null) {
                    double lat = ((Number) cached.get("lat")).doubleValue();
                    double lon = ((Number) cached.get("lon")).doubleValue();
                    if (outName != null && outName.length > 0) {
                        outName[0] = (String) cached.get("displayName");
                    }
                    log.info("Geocoding CACHE HIT for: '{}' -> Lat: {}, Lng: {}", address, lat, lon);
                    return new double[]{lat, lon};
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read from Redis geocode cache for: '{}' - error: {}", address, e.getMessage());
        }

        java.net.URI uri = null;
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "VoltFlowIndia-EV-Station-Finder/1.0 (contact@voltflowindia.com)");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            uri = UriComponentsBuilder.fromHttpUrl("https://nominatim.openstreetmap.org/search")
                    .queryParam("q", address)
                    .queryParam("format", "json")
                    .queryParam("limit", 1)
                    .build()
                    .encode()
                    .toUri();

            log.info("Nominatim Geocoding Request URI: {}", uri);

            ResponseEntity<List> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    entity,
                    List.class
            );

            log.info("Nominatim response status: {}", response.getStatusCode());

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<?> body = response.getBody();
                log.info("Nominatim response body size: {}", body.size());
                if (!body.isEmpty()) {
                    Map<?, ?> match = (Map<?, ?>) body.get(0);
                    double lat = Double.parseDouble(match.get("lat").toString());
                    double lon = Double.parseDouble(match.get("lon").toString());
                    if (outName != null && outName.length > 0) {
                        outName[0] = match.get("display_name").toString();
                    }
                    log.info("Successfully geocoded '{}' to Lat: {}, Lng: {}", address, lat, lon);
                    double[] result = new double[]{lat, lon};
                    
                    try {
                        if (redisTemplate != null) {
                            Map<String, Object> cacheData = new java.util.HashMap<>();
                            cacheData.put("lat", lat);
                            cacheData.put("lon", lon);
                            cacheData.put("displayName", outName[0]);
                            redisTemplate.opsForValue().set(cacheKey, cacheData, 24, java.util.concurrent.TimeUnit.HOURS);
                            log.info("Geocoding cached in Redis for address: '{}'", address);
                        }
                    } catch (Exception ex) {
                        log.warn("Failed to write to Redis geocode cache for: '{}' - error: {}", address, ex.getMessage());
                    }
                    
                    return result;
                } else {
                    log.warn("Nominatim returned empty results for address: '{}'", address);
                }
            } else {
                log.error("Nominatim request failed with status: {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Geocoding exception failed for address: " + address + " (URI: " + uri + ")", e);
        }
        return null;
    }

    private List<double[]> fetchRouteFromOSRM(double[] start, double[] end, double[] outDistanceKm, double[] outDurationSec) {
        List<double[]> routePoints = new ArrayList<>();
        java.net.URI uri = null;
        try {
            RestTemplate restTemplate = new RestTemplate();
            
            uri = UriComponentsBuilder.fromHttpUrl("https://router.project-osrm.org/route/v1/driving/" + start[1] + "," + start[0] + ";" + end[1] + "," + end[0])
                    .queryParam("overview", "full")
                    .queryParam("geometries", "geojson")
                    .build(true) // build without escaping coordinate separator ';'
                    .toUri();

            log.info("OSRM Request URI: {}", uri);

            Map<?, ?> response = restTemplate.getForObject(uri, Map.class);
            if (response != null && response.containsKey("routes")) {
                List<?> routes = (List<?>) response.get("routes");
                if (routes != null && !routes.isEmpty()) {
                    Map<?, ?> route = (Map<?, ?>) routes.get(0);
                    
                    if (outDistanceKm != null && outDistanceKm.length > 0 && route.containsKey("distance")) {
                        outDistanceKm[0] = ((Number) route.get("distance")).doubleValue() / 1000.0;
                    }
                    if (outDurationSec != null && outDurationSec.length > 0 && route.containsKey("duration")) {
                        outDurationSec[0] = ((Number) route.get("duration")).doubleValue();
                    }

                    Map<?, ?> geometry = (Map<?, ?>) route.get("geometry");
                    if (geometry != null && "LineString".equals(geometry.get("type"))) {
                        List<?> coords = (List<?>) geometry.get("coordinates");
                        if (coords != null) {
                            for (Object coordObj : coords) {
                                List<?> point = (List<?>) coordObj;
                                if (point.size() >= 2) {
                                    double lon = ((Number) point.get(0)).doubleValue();
                                    double lat = ((Number) point.get(1)).doubleValue();
                                    routePoints.add(new double[]{lat, lon});
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("OSRM routing failed (URI: " + uri + ")", e);
        }

        // Fallback to straight line if OSRM failed
        if (routePoints.isEmpty()) {
            log.warn("OSRM returned no route points. Falling back to straight line path.");
            routePoints.add(start);
            routePoints.add(end);
            if (outDistanceKm != null && outDistanceKm.length > 0) {
                outDistanceKm[0] = calculateDistance(start[0], start[1], end[0], end[1]);
            }
            if (outDurationSec != null && outDurationSec.length > 0) {
                outDurationSec[0] = (outDistanceKm[0] / 60.0) * 3600.0;
            }
        }

        return routePoints;
    }
}
