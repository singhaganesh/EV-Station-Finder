package com.ganesh.finder.controller;

import com.ganesh.finder.dto.ApiResponse;
import com.ganesh.finder.model.AppUser;
import com.ganesh.finder.model.Favorite;
import com.ganesh.finder.model.UserVehicle;
import com.ganesh.finder.model.Station;
import com.ganesh.finder.repository.FavoriteRepository;
import com.ganesh.finder.repository.UserVehicleRepository;
import com.ganesh.finder.repository.StationRepository;
import com.ganesh.finder.service.AppUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/me")
public class UserController {

    private final AppUserService appUserService;
    private final FavoriteRepository favoriteRepository;
    private final UserVehicleRepository userVehicleRepository;
    private final StationRepository stationRepository;

    public UserController(AppUserService appUserService,
                          FavoriteRepository favoriteRepository,
                          UserVehicleRepository userVehicleRepository,
                          StationRepository stationRepository) {
        this.appUserService = appUserService;
        this.favoriteRepository = favoriteRepository;
        this.userVehicleRepository = userVehicleRepository;
        this.stationRepository = stationRepository;
    }

    /**
     * GET /api/me/profile
     */
    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<?>> getMyProfile(@AuthenticationPrincipal Jwt jwt) {
        try {
            AppUser user = appUserService.getOrCreate(jwt);
            return ResponseEntity.ok(ApiResponse.success("Successfully fetched user profile", user));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to fetch profile: " + e.getMessage()));
        }
    }

    /**
     * GET /api/me/favorites
     */
    @GetMapping("/favorites")
    public ResponseEntity<ApiResponse<?>> getMyFavorites(@AuthenticationPrincipal Jwt jwt) {
        try {
            AppUser user = appUserService.getOrCreate(jwt);
            List<Favorite> favorites = favoriteRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
            List<Station> stations = favorites.stream()
                    .map(Favorite::getStation)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success("Successfully fetched favorites", stations));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to fetch favorites: " + e.getMessage()));
        }
    }

    /**
     * POST /api/me/favorites/{stationId}
     */
    @PostMapping("/favorites/{stationId}")
    public ResponseEntity<ApiResponse<?>> addFavorite(@AuthenticationPrincipal Jwt jwt, @PathVariable Long stationId) {
        try {
            AppUser user = appUserService.getOrCreate(jwt);
            Station station = stationRepository.findById(stationId)
                    .orElseThrow(() -> new IllegalArgumentException("Station not found with id: " + stationId));

            if (!favoriteRepository.existsByUserIdAndStationId(user.getId(), stationId)) {
                Favorite favorite = Favorite.builder()
                        .user(user)
                        .station(station)
                        .build();
                favoriteRepository.save(favorite);
            }
            return ResponseEntity.ok(ApiResponse.success("Station added to favorites", null));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to add favorite: " + e.getMessage()));
        }
    }

    /**
     * DELETE /api/me/favorites/{stationId}
     */
    @DeleteMapping("/favorites/{stationId}")
    public ResponseEntity<ApiResponse<?>> removeFavorite(@AuthenticationPrincipal Jwt jwt, @PathVariable Long stationId) {
        try {
            AppUser user = appUserService.getOrCreate(jwt);
            Optional<Favorite> favorite = favoriteRepository.findByUserIdAndStationId(user.getId(), stationId);
            favorite.ifPresent(favoriteRepository::delete);
            return ResponseEntity.ok(ApiResponse.success("Station removed from favorites", null));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to remove favorite: " + e.getMessage()));
        }
    }

    /**
     * GET /api/me/vehicles
     */
    @GetMapping("/vehicles")
    public ResponseEntity<ApiResponse<?>> getMyVehicles(@AuthenticationPrincipal Jwt jwt) {
        try {
            AppUser user = appUserService.getOrCreate(jwt);
            List<UserVehicle> vehicles = userVehicleRepository.findByUserId(user.getId());
            return ResponseEntity.ok(ApiResponse.success("Successfully fetched vehicles", vehicles));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to fetch vehicles: " + e.getMessage()));
        }
    }

    /**
     * POST /api/me/vehicles
     */
    @PostMapping("/vehicles")
    public ResponseEntity<ApiResponse<?>> saveVehicle(@AuthenticationPrincipal Jwt jwt, @RequestBody UserVehicle vehicleReq) {
        try {
            AppUser user = appUserService.getOrCreate(jwt);
            List<UserVehicle> existing = userVehicleRepository.findByUserId(user.getId());
            UserVehicle vehicle;
            if (!existing.isEmpty()) {
                vehicle = existing.get(0);
                vehicle.setModelName(vehicleReq.getModelName());
                vehicle.setBatteryCapacity(vehicleReq.getBatteryCapacity());
                vehicle.setRangeKm(vehicleReq.getRangeKm());
                vehicle.setPreferredConnector(vehicleReq.getPreferredConnector());
                vehicle.setMinPowerKw(vehicleReq.getMinPowerKw());
                vehicle.setOnlyOpen(vehicleReq.getOnlyOpen());
                vehicle.setOnlyAvailable(vehicleReq.getOnlyAvailable());
            } else {
                vehicle = vehicleReq;
                vehicle.setUser(user);
            }
            UserVehicle saved = userVehicleRepository.save(vehicle);
            return ResponseEntity.ok(ApiResponse.success("Vehicle saved successfully", saved));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to save vehicle: " + e.getMessage()));
        }
    }

    /**
     * PUT /api/me/profile
     */
    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<?>> updateProfile(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody AppUser profileUpdate) {
        try {
            AppUser user = appUserService.getOrCreate(jwt);
            AppUser updated = appUserService.updateProfile(
                    user.getId(),
                    profileUpdate.getDisplayName(),
                    profileUpdate.getAvatarUrl()
            );
            return ResponseEntity.ok(ApiResponse.success("Successfully updated user profile", updated));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to update profile: " + e.getMessage()));
        }
    }

    /**
     * DELETE /api/me
     */
    @DeleteMapping
    public ResponseEntity<ApiResponse<?>> deleteAccount(@AuthenticationPrincipal Jwt jwt) {
        try {
            AppUser user = appUserService.getOrCreate(jwt);
            appUserService.deleteUser(user.getId());
            return ResponseEntity.ok(ApiResponse.success("Account deleted successfully", null));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to delete account: " + e.getMessage()));
        }
    }
}
