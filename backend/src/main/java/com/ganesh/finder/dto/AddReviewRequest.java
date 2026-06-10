package com.ganesh.finder.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Validated payload for POST /api/stations/{id}/reviews.
 * Rating is bounded server-side (1..5) so a crafted client cannot poison
 * a station's average rating; the comment is length-capped.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddReviewRequest {

    @NotNull(message = "rating is required")
    @DecimalMin(value = "1.0", message = "rating must be between 1 and 5")
    @DecimalMax(value = "5.0", message = "rating must be between 1 and 5")
    private Double rating;

    @Size(max = 2000, message = "comment must be 2000 characters or fewer")
    private String comment;
}
