package com.ganesh.stationfinder.data.network

import com.ganesh.stationfinder.data.model.ApiResponse
import com.ganesh.stationfinder.data.model.OCMStation
import com.ganesh.stationfinder.data.model.Review
import com.ganesh.stationfinder.data.model.StationMarker
import com.ganesh.stationfinder.data.model.RoutePlanResponse
import retrofit2.http.*

interface OpenChargeMapApi {
    
    @GET("api/stations/nearby")
    suspend fun getNearbyStations(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("radius") radius: Double,
        @Query("limit") limit: Int = 20
    ): ApiResponse<List<OCMStation>>

    @GET("api/stations/{id}/reviews")
    suspend fun getReviews(
        @Path("id") id: Long
    ): ApiResponse<List<Review>>

    @POST("api/stations/{id}/reviews")
    suspend fun addReview(
        @Path("id") id: Long,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): ApiResponse<Review>

    @GET("api/stations/route")
    suspend fun getStationsAlongRoute(
        @Query("waypoints") waypoints: String,
        @Query("connectorType") connectorType: String?,
        @Query("bufferKm") bufferKm: Double = 10.0
    ): ApiResponse<List<OCMStation>>

    @GET("api/stations/route/plan")
    suspend fun planRoute(
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("connectorType") connectorType: String?
    ): ApiResponse<RoutePlanResponse>

    @GET("api/stations/viewport")
    suspend fun getStationsInViewport(
        @Query("neLat") neLat: Double,
        @Query("neLng") neLng: Double,
        @Query("swLat") swLat: Double,
        @Query("swLng") swLng: Double,
        @Query("connectorType") connectorType: String? = null
    ): ApiResponse<List<StationMarker>>

    @GET("api/stations/{id}/detail")
    suspend fun getStationDetail(
        @Path("id") id: Long,
        @Query("lat") lat: Double,
        @Query("lng") lng: Double
    ): ApiResponse<OCMStation>

    @GET("api/me/favorites")
    suspend fun getFavorites(): ApiResponse<List<OCMStation>>

    @POST("api/me/favorites/{stationId}")
    suspend fun addFavorite(@Path("stationId") stationId: Long): ApiResponse<Unit?>

    @DELETE("api/me/favorites/{stationId}")
    suspend fun removeFavorite(@Path("stationId") stationId: Long): ApiResponse<Unit?>

    @GET("api/me/vehicles")
    suspend fun getVehicles(): ApiResponse<List<com.ganesh.stationfinder.data.model.UserVehicle>>

    @POST("api/me/vehicles")
    suspend fun saveVehicle(@Body vehicle: com.ganesh.stationfinder.data.model.UserVehicle): ApiResponse<com.ganesh.stationfinder.data.model.UserVehicle>

    @PUT("api/me/profile")
    suspend fun updateProfile(@Body profile: com.ganesh.stationfinder.data.model.UserProfile): ApiResponse<com.ganesh.stationfinder.data.model.UserProfile>

    @DELETE("api/me")
    suspend fun deleteAccount(): ApiResponse<Unit?>
}

