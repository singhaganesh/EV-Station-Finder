package com.ganesh.stationfinder.data.model

import com.google.gson.annotations.SerializedName
import org.json.JSONObject

data class ApiResponse<T>(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("data") val data: T
)

data class OCMStation(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("address") val address: String?,
    @SerializedName("operatingHours") val operatingHours: String?,
    @SerializedName("pricePerKwh") val pricePerKwh: Double?,
    @SerializedName("rating") val rating: Double?,
    @SerializedName("isOpen") val isOpen: Boolean?,
    @SerializedName("meta") val meta: String?,
    @SerializedName("distance") val distance: Double?,
    @SerializedName("availableSlots") val availableSlots: Int?,
    @SerializedName("totalSlots") val totalSlots: Int?,
    @SerializedName("connectorTypes") val connectorTypes: List<String>?,
    @SerializedName("slots") val slots: List<SlotInfo>?,
    // ISO timestamp of the last OCM sync. Availability shown in the UI reflects this
    // import, not a live feed, so it should be labelled "as of <lastSynced>".
    @SerializedName("lastSynced") val lastSynced: String? = null
) {
    val operatorName: String
        get() {
            if (meta.isNullOrEmpty()) return "Independent Operator"
            // meta is JSON; parse it properly instead of a fragile regex.
            return try {
                JSONObject(meta).optString("ocm_operator").ifBlank { "Independent Operator" }
            } catch (e: Exception) {
                "Independent Operator"
            }
        }

    /** Human-friendly "data freshness" date (yyyy-MM-dd) derived from lastSynced, or null. */
    val lastSyncedDate: String?
        get() = lastSynced?.take(10)?.ifBlank { null }
}

data class SlotInfo(
    @SerializedName("id") val id: Long,
    @SerializedName("label") val label: String?,
    @SerializedName("connectorType") val connectorType: String?,
    @SerializedName("powerKw") val powerKw: Double?,
    @SerializedName("isAvailable") val isAvailable: Boolean?
)

data class Review(
    @SerializedName("id") val id: Long,
    @SerializedName("reviewerName") val reviewerName: String,
    @SerializedName("rating") val rating: Double,
    @SerializedName("comment") val comment: String?,
    @SerializedName("createdAt") val createdAt: String?
)

data class StationMarker(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("available") val available: Boolean,
    @SerializedName("rating") val rating: Double?,
    @SerializedName("distance") val distance: Double?,
    @SerializedName("availableSlots") val availableSlots: Int?,
    @SerializedName("totalSlots") val totalSlots: Int?,
    @SerializedName("connectorTypes") val connectorTypes: List<String>?
)

data class RoutePlanResponse(
    @SerializedName("fromName") val fromName: String,
    @SerializedName("toName") val toName: String,
    @SerializedName("distanceKm") val distanceKm: Double,
    @SerializedName("durationSec") val durationSec: Double,
    @SerializedName("routePoints") val routePoints: List<List<Double>>,
    @SerializedName("stations") val stations: List<OCMStation>
)



