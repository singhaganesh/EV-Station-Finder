package com.ganesh.finder.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_vehicles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserVehicle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false)
    private String modelName;

    private String batteryCapacity; // e.g. "40"
    
    private String rangeKm; // e.g. "300"

    private String preferredConnector; // e.g. "CCS2"

    private Integer minPowerKw;

    private Boolean onlyOpen;

    private Boolean onlyAvailable;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
