package com.ganesh.finder.repository;

import com.ganesh.finder.model.Favorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface FavoriteRepository extends JpaRepository<Favorite, Long> {
    List<Favorite> findByUserIdOrderByCreatedAtDesc(String userId);
    Optional<Favorite> findByUserIdAndStationId(String userId, Long stationId);
    boolean existsByUserIdAndStationId(String userId, Long stationId);
    void deleteByUserIdAndStationId(String userId, Long stationId);
    void deleteByUserId(String userId);
}
