package com.ganesh.finder.repository;

import com.ganesh.finder.model.ChargerSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ChargerSlotRepository extends JpaRepository<ChargerSlot, Long> {

    List<ChargerSlot> findByStationId(Long stationId);

    List<ChargerSlot> findByStationIdAndIsAvailableTrue(Long stationId);

    long countByStationIdAndIsAvailableTrue(Long stationId);

    long countByStationId(Long stationId);

    void deleteByStationId(Long stationId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM ChargerSlot c WHERE c.station.id IN :stationIds")
    void deleteByStationIdIn(@org.springframework.data.repository.query.Param("stationIds") List<Long> stationIds);
}

