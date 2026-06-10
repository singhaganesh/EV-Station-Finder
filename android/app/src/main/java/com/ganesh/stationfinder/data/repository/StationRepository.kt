package com.ganesh.stationfinder.data.repository

import com.ganesh.stationfinder.data.model.OCMStation
import com.ganesh.stationfinder.data.model.StationMarker
import com.ganesh.stationfinder.data.model.RoutePlanResponse
import com.ganesh.stationfinder.data.network.RetrofitClient

class StationRepository {
    
    private val api = RetrofitClient.api

    /**
     * Throws on network/transport failure so the ViewModel can distinguish a real
     * error (show a snackbar) from a genuinely empty viewport (no markers, no error).
     */
    suspend fun getStationsInViewport(
        neLat: Double, neLng: Double, swLat: Double, swLng: Double,
        connectorType: String? = null
    ): Pair<List<StationMarker>, Boolean> {
        val response = api.getStationsInViewport(neLat, neLng, swLat, swLng, connectorType)
        if (!response.success) {
            throw IllegalStateException(response.message.ifBlank { "Failed to load stations" })
        }
        val tooMany = response.message.contains("Too many", ignoreCase = true)
        return Pair(response.data, tooMany)
    }

    suspend fun getNearbyStations(lat: Double, lng: Double, distance: Double = 20.0): List<OCMStation> {
        android.util.Log.d("Repository", "Fetching stations at Lat: $lat, Lng: $lng (Radius: ${distance}km)")
        return try {
            val response = api.getNearbyStations(
                lat = lat,
                lng = lng,
                radius = distance
            )
            if (response.success) {
                android.util.Log.d("Repository", "API returned ${response.data.size} stations: ${response.message}")
                response.data
            } else {
                android.util.Log.e("Repository", "API returned failure: ${response.message}")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("Repository", "Error fetching stations", e)
            emptyList()
        }
    }

    suspend fun getReviews(stationId: Long): List<com.ganesh.stationfinder.data.model.Review> {
        return try {
            val response = api.getReviews(stationId)
            if (response.success) response.data else emptyList()
        } catch (e: Exception) {
            android.util.Log.e("Repository", "Error getting reviews", e)
            emptyList()
        }
    }

    suspend fun addReview(stationId: Long, reviewerName: String, rating: Double, comment: String): com.ganesh.stationfinder.data.model.Review? {
        return try {
            val body = mapOf(
                "reviewerName" to reviewerName,
                "rating" to rating,
                "comment" to comment
            )
            val response = api.addReview(stationId, body)
            if (response.success) response.data else null
        } catch (e: Exception) {
            android.util.Log.e("Repository", "Error adding review", e)
            null
        }
    }

    suspend fun getStationsAlongRoute(waypoints: String, connectorType: String?): List<OCMStation> {
        return try {
            val response = api.getStationsAlongRoute(waypoints, connectorType)
            if (response.success) response.data else emptyList()
        } catch (e: Exception) {
            android.util.Log.e("Repository", "Error getting stations along route", e)
            emptyList()
        }
    }

    suspend fun getStationDetail(id: Long, lat: Double, lng: Double): OCMStation? {
        return try {
            val response = api.getStationDetail(id, lat, lng)
            if (response.success) response.data else null
        } catch (e: Exception) {
            android.util.Log.e("Repository", "Error fetching station detail", e)
            null
        }
    }

    suspend fun planRoute(from: String, to: String, connectorType: String?): RoutePlanResponse? {
        return try {
            val response = api.planRoute(from, to, connectorType)
            if (response.success) response.data else null
        } catch (e: Exception) {
            android.util.Log.e("Repository", "Error planning route from $from to $to", e)
            null
        }
    }

    suspend fun getFavorites(): List<OCMStation> {
        return try {
            val response = api.getFavorites()
            if (response.success) response.data else emptyList()
        } catch (e: Exception) {
            android.util.Log.e("Repository", "Error getting favorites from cloud", e)
            emptyList()
        }
    }

    suspend fun addFavorite(stationId: Long): Boolean {
        return try {
            api.addFavorite(stationId).success
        } catch (e: Exception) {
            android.util.Log.e("Repository", "Error adding favorite to cloud", e)
            false
        }
    }

    suspend fun removeFavorite(stationId: Long): Boolean {
        return try {
            api.removeFavorite(stationId).success
        } catch (e: Exception) {
            android.util.Log.e("Repository", "Error removing favorite from cloud", e)
            false
        }
    }

    suspend fun getVehicles(): List<com.ganesh.stationfinder.data.model.UserVehicle> {
        return try {
            val response = api.getVehicles()
            if (response.success) response.data else emptyList()
        } catch (e: Exception) {
            android.util.Log.e("Repository", "Error getting vehicles from cloud", e)
            emptyList()
        }
    }

    suspend fun saveVehicle(vehicle: com.ganesh.stationfinder.data.model.UserVehicle): com.ganesh.stationfinder.data.model.UserVehicle? {
        return try {
            val response = api.saveVehicle(vehicle)
            if (response.success) response.data else null
        } catch (e: Exception) {
            android.util.Log.e("Repository", "Error saving vehicle to cloud", e)
            null
        }
    }

    suspend fun updateProfile(profile: com.ganesh.stationfinder.data.model.UserProfile): com.ganesh.stationfinder.data.model.UserProfile? {
        return try {
            val response = api.updateProfile(profile)
            if (response.success) response.data else null
        } catch (e: Exception) {
            android.util.Log.e("Repository", "Error updating profile", e)
            null
        }
    }

    suspend fun deleteAccount(): Boolean {
        return try {
            api.deleteAccount().success
        } catch (e: Exception) {
            android.util.Log.e("Repository", "Error deleting account", e)
            false
        }
    }
}
