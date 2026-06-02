package com.ganesh.finder.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutePlanResponse {
    private String fromName;
    private String toName;
    private Double distanceKm;
    private Double durationSec;
    private List<double[]> routePoints;
    private List<StationWithScore> stations;
}
