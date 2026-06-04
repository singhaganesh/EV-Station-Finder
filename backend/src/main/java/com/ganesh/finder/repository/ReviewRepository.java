package com.ganesh.finder.repository;

import com.ganesh.finder.model.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByStationIdOrderByCreatedAtDesc(Long stationId);

    boolean existsByStationIdAndUserId(Long stationId, String userId);

    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.station.id = :stationId")
    Double getAverageRatingForStation(@Param("stationId") Long stationId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM Review r WHERE r.station.id IN :stationIds")
    void deleteByStationIdIn(@org.springframework.data.repository.query.Param("stationIds") List<Long> stationIds);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE Review r SET r.user = null WHERE r.user.id = :userId")
    void nullifyUserId(@org.springframework.data.repository.query.Param("userId") String userId);
}

