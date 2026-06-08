package com.ganesh.finder.service;

import com.ganesh.finder.model.AppUser;
import com.ganesh.finder.repository.AppUserRepository;
import com.ganesh.finder.repository.FavoriteRepository;
import com.ganesh.finder.repository.UserVehicleRepository;
import com.ganesh.finder.repository.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.Optional;

@Service
public class AppUserService {

    private static final Logger log = LoggerFactory.getLogger(AppUserService.class);

    private final AppUserRepository appUserRepository;
    private final FavoriteRepository favoriteRepository;
    private final UserVehicleRepository userVehicleRepository;
    private final ReviewRepository reviewRepository;

    public AppUserService(AppUserRepository appUserRepository,
                          FavoriteRepository favoriteRepository,
                          UserVehicleRepository userVehicleRepository,
                          ReviewRepository reviewRepository) {
        this.appUserRepository = appUserRepository;
        this.favoriteRepository = favoriteRepository;
        this.userVehicleRepository = userVehicleRepository;
        this.reviewRepository = reviewRepository;
    }

    public AppUser getOrCreate(Jwt jwt) {
        String userId = jwt.getSubject();
        
        Optional<AppUser> existing = appUserRepository.findById(userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        log.info("Creating new AppUser for subject UUID: {}", userId);
        String email = jwt.getClaimAsString("email");
        
        String displayName = "User";
        String avatarUrl = null;

        Map<String, Object> userMetadata = jwt.getClaimAsMap("user_metadata");
        if (userMetadata != null) {
            if (userMetadata.containsKey("name")) {
                displayName = userMetadata.get("name").toString();
            } else if (userMetadata.containsKey("full_name")) {
                displayName = userMetadata.get("full_name").toString();
            }
            
            if (userMetadata.containsKey("avatar_url")) {
                avatarUrl = userMetadata.get("avatar_url").toString();
            } else if (userMetadata.containsKey("picture")) {
                avatarUrl = userMetadata.get("picture").toString();
            }
        }

        AppUser newUser = AppUser.builder()
                .id(userId)
                .displayName(displayName)
                .email(email)
                .avatarUrl(avatarUrl)
                .build();

        return appUserRepository.save(newUser);
    }

    @Transactional
    public AppUser updateProfile(String userId, String displayName, String avatarUrl) {
        log.info("Updating profile for AppUser UUID: {}", userId);
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with id: " + userId));
        if (displayName != null && !displayName.trim().isEmpty()) {
            user.setDisplayName(displayName);
        }
        if (avatarUrl != null) {
            user.setAvatarUrl(avatarUrl);
        }
        return appUserRepository.save(user);
    }

    @Transactional
    public void deleteUser(String userId) {
        log.info("Deleting AppUser and cascading related records for UUID: {}", userId);
        favoriteRepository.deleteByUserId(userId);
        userVehicleRepository.deleteByUserId(userId);
        reviewRepository.nullifyUserId(userId);
        appUserRepository.deleteById(userId);
    }
}
