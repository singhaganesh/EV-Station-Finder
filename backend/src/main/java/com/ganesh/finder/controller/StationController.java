package com.ganesh.finder.controller;

import com.ganesh.finder.dto.AddReviewRequest;
import com.ganesh.finder.dto.ApiResponse;
import com.ganesh.finder.dto.StationWithScore;
import com.ganesh.finder.dto.RoutePlanResponse;
import com.ganesh.finder.exception.ResourceNotFoundException;
import com.ganesh.finder.service.StationService;
import com.ganesh.finder.service.AppUserService;
import com.ganesh.finder.model.AppUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

/**
 * Thin controller: no try/catch. Failures propagate to {@code GlobalExceptionHandler}
 * which maps them to the correct HTTP status and JSON envelope.
 */
@RestController
@RequestMapping("/api/stations")
public class StationController {

    private final StationService stationService;
    private final AppUserService appUserService;

    public StationController(StationService stationService, AppUserService appUserService) {
        this.stationService = stationService;
        this.appUserService = appUserService;
    }

    @GetMapping("/nearby")
    public ResponseEntity<ApiResponse<?>> getNearbyStations(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "50") double radius,
            @RequestParam(defaultValue = "20") int limit) {
        List<StationWithScore> stations = stationService.getNearbyStations(lat, lng, radius, limit);
        return ResponseEntity.ok(ApiResponse.success("Found " + stations.size() + " nearby stations", stations));
    }

    @GetMapping("/viewport")
    public ResponseEntity<ApiResponse<?>> getStationsInViewport(
            @RequestParam double neLat,
            @RequestParam double neLng,
            @RequestParam double swLat,
            @RequestParam double swLng,
            @RequestParam(required = false) String connectorType) {
        StationService.ViewportResponse result = stationService.getStationsInViewportOptimized(
                neLat, neLng, swLat, swLng, connectorType);
        String msg = result.tooMany()
                ? "Too many stations, showing nearest 200. Zoom in for more."
                : "Found " + result.markers().size() + " stations in viewport";
        return ResponseEntity.ok(ApiResponse.success(msg, result.markers()));
    }

    @GetMapping("/{id}/detail")
    public ResponseEntity<ApiResponse<?>> getStationDetail(
            @PathVariable Long id,
            @RequestParam double lat,
            @RequestParam double lng) {
        var detail = stationService.getStationDetail(id, lat, lng)
                .orElseThrow(() -> new ResourceNotFoundException("Station not found"));
        return ResponseEntity.ok(ApiResponse.success("Station found", detail));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<?>> searchStations(
            @RequestParam String q,
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "50") double radius) {
        List<StationWithScore> results = stationService.searchStations(q, lat, lng, radius);
        return ResponseEntity.ok(ApiResponse.success(
                "Found " + results.size() + " stations matching \"" + q + "\"", results));
    }

    @GetMapping("/count")
    public ResponseEntity<ApiResponse<?>> getStationCount() {
        long count = stationService.getStationCount();
        return ResponseEntity.ok(ApiResponse.success("OK", Map.of("count", count)));
    }

    @GetMapping("/{id}/reviews")
    public ResponseEntity<ApiResponse<?>> getReviews(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Success", stationService.getReviewsForStation(id)));
    }

    @PostMapping("/{id}/reviews")
    public ResponseEntity<ApiResponse<?>> addReview(
            @PathVariable Long id,
            @Valid @RequestBody AddReviewRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        AppUser user = appUserService.getOrCreate(jwt);
        var review = stationService.addReviewForStation(id, user, request.getRating(), request.getComment());
        return ResponseEntity.ok(ApiResponse.success("Review added successfully", review));
    }

    @GetMapping("/route/plan")
    public ResponseEntity<ApiResponse<?>> planRoute(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String connectorType) {
        RoutePlanResponse plan = stationService.planRoute(from, to, connectorType);
        return ResponseEntity.ok(ApiResponse.success("Successfully planned route", plan));
    }

    @GetMapping("/route")
    public ResponseEntity<ApiResponse<?>> getStationsAlongRoute(
            @RequestParam String waypoints,
            @RequestParam(required = false) String connectorType,
            @RequestParam(defaultValue = "10.0") double bufferKm) {
        var stations = stationService.getStationsAlongRoute(waypoints, connectorType, bufferKm);
        return ResponseEntity.ok(ApiResponse.success("Found " + stations.size() + " stations along route", stations));
    }

    @PostMapping("/cleanup-duplicates")
    public ResponseEntity<ApiResponse<?>> cleanupDuplicates() {
        int deletedCount = stationService.removeDuplicateStations();
        return ResponseEntity.ok(ApiResponse.success(
                "Cleanup completed. Removed " + deletedCount + " duplicate stations.",
                Map.of("deletedCount", deletedCount)));
    }
}
