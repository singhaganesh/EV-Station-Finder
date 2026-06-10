package com.ganesh.stationfinder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ganesh.stationfinder.data.model.OCMStation
import com.ganesh.stationfinder.data.model.Review
import com.ganesh.stationfinder.data.model.StationMarker
import com.ganesh.stationfinder.data.model.UserVehicle
import com.ganesh.stationfinder.data.repository.StationRepository
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.CameraPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.LinkedHashMap
import com.ganesh.stationfinder.util.FavoriteManager

sealed class StationUiState {
    object Loading : StationUiState()
    data class Success(val stations: List<OCMStation>) : StationUiState()
    data class Error(val message: String) : StationUiState()
}

sealed class MarkerUiState {
    object Idle : MarkerUiState()
    object Loading : MarkerUiState()
    data class Success(val markers: List<StationMarker>, val tooMany: Boolean = false) : MarkerUiState()
    data class Error(val message: String) : MarkerUiState()
}

sealed class AuthState {
    object Loading : AuthState()
    object SignedOut : AuthState()
    data class SignedIn(val profile: com.ganesh.stationfinder.data.model.UserProfile) : AuthState()
}

class StationViewModel : ViewModel() {
    private val repository = StationRepository()
    private var searchJob: Job? = null
    var lastFetchedLocation: LatLng? = null
        private set

    // --- Map View State Preservation ---
    var mapCameraPosition: CameraPosition? = null
    var selectedMarkerId: Long? = null

    // --- Auth State ---
    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    fun refreshAuthState() {
        viewModelScope.launch {
            try {
                val profile = com.ganesh.stationfinder.data.network.AuthManager.currentProfile()
                _authState.value = if (profile != null) {
                    AuthState.SignedIn(profile)
                } else {
                    AuthState.SignedOut
                }
            } catch (e: Exception) {
                _authState.value = AuthState.SignedOut
            }
        }
    }

    fun refreshAuthState(context: android.content.Context) {
        viewModelScope.launch {
            val wasSignedIn = authState.value is AuthState.SignedIn
            try {
                val profile = com.ganesh.stationfinder.data.network.AuthManager.currentProfile()
                if (profile != null) {
                    _authState.value = AuthState.SignedIn(profile)
                    if (!wasSignedIn) {
                        syncFavorites(context)
                        syncVehicle(context)
                    }
                } else {
                    _authState.value = AuthState.SignedOut
                }
            } catch (e: Exception) {
                _authState.value = AuthState.SignedOut
            }
        }
    }

    // Transient one-shot message surfaced to the user (sync failures, etc.).
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()
    fun clearUserMessage() { _userMessage.value = null }
    private fun postMessage(message: String) { _userMessage.value = message }

    // Guards against rapid double-taps firing duplicate favorite toggles per station.
    private val favoriteInFlight = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    fun signOut(context: android.content.Context) {
        viewModelScope.launch {
            try {
                com.ganesh.stationfinder.data.network.AuthManager.signOut()
            } catch (ignored: Exception) {}
            FavoriteManager.clear(context)
            _authState.value = AuthState.SignedOut
            _savedStations.value = emptyList()
        }
    }

    private fun syncFavorites(context: android.content.Context) {
        viewModelScope.launch {
            try {
                // Upload every local favorite first (local-wins merge). Only overwrite the
                // local set with the cloud set if ALL uploads succeeded, so a transient
                // failure can't silently drop a bookmark.
                val localIds = FavoriteManager.getFavorites(context)
                var allUploaded = true
                for (id in localIds) {
                    if (!repository.addFavorite(id)) allUploaded = false
                }
                val cloudStations = repository.getFavorites()
                val cloudIds = cloudStations.map { it.id }.toSet()
                if (allUploaded) {
                    FavoriteManager.setFavorites(context, cloudIds)
                } else {
                    // Keep the union so nothing is lost; surface the partial failure.
                    FavoriteManager.setFavorites(context, cloudIds + localIds)
                    postMessage("Some saved stations couldn't sync. We'll retry next time.")
                }

                val lastLoc = lastFetchedLocation
                fetchSavedStations(context, lastLoc?.latitude ?: 0.0, lastLoc?.longitude ?: 0.0)
            } catch (e: Exception) {
                android.util.Log.e("ViewModel", "Error syncing favorites on sign-in", e)
                postMessage("Couldn't sync your saved stations.")
            }
        }
    }

    private fun syncVehicle(context: android.content.Context) {
        viewModelScope.launch {
            try {
                val localModel = FavoriteManager.getVehicleModel(context)
                val localBattery = FavoriteManager.getBatteryCapacity(context)
                val localRange = FavoriteManager.getRange(context)
                val localConnector = FavoriteManager.getPreferredConnector(context)
                val localMinPower = FavoriteManager.getMinPower(context)
                val localOnlyOpen = FavoriteManager.getOnlyOpen(context)
                val localOnlyAvailable = FavoriteManager.getOnlyAvailable(context)
                
                val cloudVehicles = repository.getVehicles()
                if (cloudVehicles.isEmpty()) {
                    val userVehicle = UserVehicle(
                        modelName = localModel,
                        batteryCapacity = localBattery,
                        rangeKm = localRange,
                        preferredConnector = localConnector,
                        minPowerKw = localMinPower,
                        onlyOpen = localOnlyOpen,
                        onlyAvailable = localOnlyAvailable
                    )
                    repository.saveVehicle(userVehicle)
                } else {
                    val vehicle = cloudVehicles.first()
                    FavoriteManager.setVehicleModel(context, vehicle.modelName)
                    vehicle.batteryCapacity?.let { FavoriteManager.setBatteryCapacity(context, it) }
                    vehicle.rangeKm?.let { FavoriteManager.setRange(context, it) }
                    vehicle.preferredConnector?.let { FavoriteManager.setPreferredConnector(context, it) }
                    vehicle.minPowerKw?.let { FavoriteManager.setMinPower(context, it) }
                    vehicle.onlyOpen?.let { FavoriteManager.setOnlyOpen(context, it) }
                    vehicle.onlyAvailable?.let { FavoriteManager.setOnlyAvailable(context, it) }
                }
            } catch (e: Exception) {
                android.util.Log.e("ViewModel", "Error syncing vehicle on sign-in", e)
                postMessage("Couldn't sync your vehicle profile.")
            }
        }
    }

    fun toggleFavorite(context: android.content.Context, stationId: Long) {
        // Ignore re-entrant taps for the same station while one is already in flight.
        if (!favoriteInFlight.add(stationId)) return
        viewModelScope.launch {
            try {
                val signedIn = authState.value is AuthState.SignedIn
                if (signedIn) {
                    FavoriteManager.toggleFavorite(context, stationId)
                    val isNowFav = FavoriteManager.isFavorite(context, stationId)
                    val ok = if (isNowFav) {
                        repository.addFavorite(stationId)
                    } else {
                        repository.removeFavorite(stationId)
                    }
                    if (!ok) postMessage("Couldn't sync this bookmark to the cloud.")
                } else {
                    FavoriteManager.toggleFavorite(context, stationId)
                }
                val lastLoc = lastFetchedLocation
                fetchSavedStations(context, lastLoc?.latitude ?: 0.0, lastLoc?.longitude ?: 0.0)
            } finally {
                favoriteInFlight.remove(stationId)
            }
        }
    }

    fun updateVehicleCloud(
        context: android.content.Context,
        model: String,
        battery: String,
        range: String,
        connector: String,
        minPower: Int,
        onlyOpen: Boolean,
        onlyAvailable: Boolean
    ) {
        viewModelScope.launch {
            val signedIn = authState.value is AuthState.SignedIn
            if (signedIn) {
                try {
                    val userVehicle = UserVehicle(
                        modelName = model,
                        batteryCapacity = battery,
                        rangeKm = range,
                        preferredConnector = connector,
                        minPowerKw = minPower,
                        onlyOpen = onlyOpen,
                        onlyAvailable = onlyAvailable
                    )
                    repository.saveVehicle(userVehicle)
                } catch (e: Exception) {
                    android.util.Log.e("ViewModel", "Error updating vehicle in cloud", e)
                }
            }
        }
    }

    // --- Map markers (lightweight) ---
    private val _markerState = MutableStateFlow<MarkerUiState>(MarkerUiState.Idle)
    val markerState: StateFlow<MarkerUiState> = _markerState.asStateFlow()

    // --- Carousel (full stations, max 5) ---
    private val _carouselStations = MutableStateFlow<List<OCMStation>>(emptyList())
    val carouselStations: StateFlow<List<OCMStation>> = _carouselStations.asStateFlow()

    // --- Bounding box cache (LRU, max 10 entries) ---
    private val viewportCache = LinkedHashMap<String, Pair<List<StationMarker>, Boolean>>(10, 0.75f, true)

    private var lastViewportKey: String? = null
    private var viewportJob: Job? = null
    private var carouselJob: Job? = null

    // --- Selected Station Detail for Map pin click ---
    private val _selectedStationDetail = MutableStateFlow<OCMStation?>(null)
    val selectedStationDetail: StateFlow<OCMStation?> = _selectedStationDetail.asStateFlow()

    // --- Saved Stations (full OCMStation objects) ---
    private val _savedStations = MutableStateFlow<List<OCMStation>>(emptyList())
    val savedStations: StateFlow<List<OCMStation>> = _savedStations.asStateFlow()

    private val _isSavedLoading = MutableStateFlow(false)
    val isSavedLoading: StateFlow<Boolean> = _isSavedLoading.asStateFlow()

    fun fetchSavedStations(context: android.content.Context, lat: Double, lng: Double) {
        viewModelScope.launch {
            _isSavedLoading.value = true
            val signedIn = authState.value is AuthState.SignedIn
            if (signedIn) {
                try {
                    val cloudStations = repository.getFavorites()
                    val list = cloudStations.map { station ->
                        val dist = if (lat != 0.0 && lng != 0.0) {
                            haversineKm(lat, lng, station.latitude, station.longitude)
                        } else null
                        station.copy(distance = dist)
                    }
                    _savedStations.value = list
                } catch (e: Exception) {
                    android.util.Log.e("ViewModel", "Error fetching cloud saved stations", e)
                    _savedStations.value = emptyList()
                }
            } else {
                val ids = FavoriteManager.getFavorites(context)
                if (ids.isEmpty()) {
                    _savedStations.value = emptyList()
                } else {
                    try {
                        val list = ids.mapNotNull { id ->
                            repository.getStationDetail(id, lat, lng)
                        }
                        _savedStations.value = list
                    } catch (e: Exception) {
                        android.util.Log.e("ViewModel", "Error fetching local saved stations details", e)
                        _savedStations.value = emptyList()
                    }
                }
            }
            _isSavedLoading.value = false
        }
    }

    fun fetchViewportMarkers(neLat: Double, neLng: Double, swLat: Double, swLng: Double, connectorType: String? = null) {
        // Round to 3 decimal places for cache key (~111m precision) and include connectorType
        val key = "${(neLat*1000).toInt()},${(neLng*1000).toInt()},${(swLat*1000).toInt()},${(swLng*1000).toInt()}_${connectorType ?: "ALL"}"

        // Skip if same viewport
        if (key == lastViewportKey) return
        lastViewportKey = key

        // Check cache
        viewportCache[key]?.let { cached ->
            _markerState.value = MarkerUiState.Success(cached.first, cached.second)
            return
        }

        viewportJob?.cancel()
        viewportJob = viewModelScope.launch {
            delay(800) // debounce
            _markerState.value = MarkerUiState.Loading
            try {
                val (markers, tooMany) = repository.getStationsInViewport(neLat, neLng, swLat, swLng, connectorType)
                viewportCache[key] = Pair(markers, tooMany)
                if (viewportCache.size > 10) {
                    viewportCache.remove(viewportCache.keys.first())
                }
                _markerState.value = MarkerUiState.Success(markers, tooMany)
            } catch (e: Exception) {
                _markerState.value = MarkerUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun fetchCarouselStations(lat: Double, lng: Double, radius: Double = 10.0, immediate: Boolean = false) {
        carouselJob?.cancel()
        carouselJob = viewModelScope.launch {
            if (!immediate) {
                delay(800) // debounce
            }
            try {
                val stations = repository.getNearbyStations(lat, lng, radius)
                _carouselStations.value = stations.take(5)
            } catch (e: Exception) {
                _carouselStations.value = emptyList()
            }
        }
    }

    /**
     * Compute carousel stations instantly from already-loaded viewport markers.
     * Sorts by proximity to [pinLat]/[pinLng] (the clicked pin) but calculates
     * display distance from [userLat]/[userLng] (user's actual GPS location).
     */
    fun computeCarouselFromMarkers(
        markers: List<StationMarker>,
        pinLat: Double, pinLng: Double,
        userLat: Double, userLng: Double
    ) {
        // Cancel any pending debounced backend fetch so it doesn't overwrite us
        carouselJob?.cancel()
        carouselJob = null

        // Sort markers by distance to the clicked pin
        val sorted = markers
            .distinctBy { "${it.latitude},${it.longitude}" }
            .sortedBy { marker ->
                haversineKm(pinLat, pinLng, marker.latitude, marker.longitude)
            }
            .take(5)

        // Convert StationMarker → OCMStation with distance from user location
        _carouselStations.value = sorted.map { marker ->
            val distFromUser = haversineKm(userLat, userLng, marker.latitude, marker.longitude)
            OCMStation(
                id = marker.id,
                name = marker.name,
                latitude = marker.latitude,
                longitude = marker.longitude,
                address = null,
                operatingHours = null,
                pricePerKwh = null,
                rating = marker.rating,
                isOpen = marker.available,
                meta = null,
                distance = distFromUser,
                availableSlots = marker.availableSlots,
                totalSlots = marker.totalSlots,
                connectorTypes = marker.connectorTypes,
                slots = null
            )
        }

        // Fetch the full station details in the background for these 5 stations
        viewModelScope.launch {
            try {
                val coroutineScope = this
                val deferreds = sorted.map { marker ->
                    coroutineScope.async {
                        val distFromUser = haversineKm(userLat, userLng, marker.latitude, marker.longitude)
                        try {
                            val detail = repository.getStationDetail(marker.id, userLat, userLng)
                            detail?.copy(distance = distFromUser)
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                val fullStations = deferreds.mapNotNull { it.await() }

                if (fullStations.isNotEmpty()) {
                    val currentList = _carouselStations.value.toMutableList()
                    fullStations.forEach { fullStation ->
                        val index = currentList.indexOfFirst { it.id == fullStation.id }
                        if (index >= 0) {
                            currentList[index] = fullStation
                        }
                    }
                    _carouselStations.value = currentList
                }
            } catch (e: Exception) {
                android.util.Log.e("ViewModel", "Error fetching heavyweight details in background", e)
            }
        }
    }

    /** Haversine formula — returns distance in kilometres between two lat/lng points. */
    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    fun fetchStationDetail(id: Long, lat: Double, lng: Double) {
        viewModelScope.launch {
            try {
                val detail = repository.getStationDetail(id, lat, lng)
                _selectedStationDetail.value = detail
            } catch (e: Exception) {
                _selectedStationDetail.value = null
            }
        }
    }

    fun clearSelectedStationDetail() {
        _selectedStationDetail.value = null
    }

    // Filter Preference
    private val _selectedConnectorFilter = MutableStateFlow<String?>(null)
    val selectedConnectorFilter: StateFlow<String?> = _selectedConnectorFilter.asStateFlow()

    // Reviews State
    private val _reviews = MutableStateFlow<List<Review>>(emptyList())
    val reviews: StateFlow<List<Review>> = _reviews.asStateFlow()

    private val _isSubmittingReview = MutableStateFlow(false)
    val isSubmittingReview: StateFlow<Boolean> = _isSubmittingReview.asStateFlow()

    // Route Planner State
    private val _routeStations = MutableStateFlow<List<OCMStation>>(emptyList())
    val routeStations: StateFlow<List<OCMStation>> = _routeStations.asStateFlow()

    private val _isRouteLoading = MutableStateFlow(false)
    val isRouteLoading: StateFlow<Boolean> = _isRouteLoading.asStateFlow()

    private val _routePoints = MutableStateFlow<List<LatLng>>(emptyList())
    val routePoints: StateFlow<List<LatLng>> = _routePoints.asStateFlow()

    private val _routeDistanceKm = MutableStateFlow(0.0)
    val routeDistanceKm: StateFlow<Double> = _routeDistanceKm.asStateFlow()

    private val _routeDurationSec = MutableStateFlow(0.0)
    val routeDurationSec: StateFlow<Double> = _routeDurationSec.asStateFlow()

    private val _routeFromName = MutableStateFlow("")
    val routeFromName: StateFlow<String> = _routeFromName.asStateFlow()

    private val _routeToName = MutableStateFlow("")
    val routeToName: StateFlow<String> = _routeToName.asStateFlow()

    private val _routeError = MutableStateFlow<String?>(null)
    val routeError: StateFlow<String?> = _routeError.asStateFlow()

    fun clearRouteError() {
        _routeError.value = null
    }

    fun selectConnectorFilter(connector: String?) {
        _selectedConnectorFilter.value = connector
    }

    fun fetchReviews(stationId: Long) {
        viewModelScope.launch {
            _reviews.value = repository.getReviews(stationId)
        }
    }

    fun submitReview(stationId: Long, reviewerName: String, rating: Double, comment: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isSubmittingReview.value = true
            val review = repository.addReview(stationId, reviewerName, rating, comment)
            _isSubmittingReview.value = false
            if (review != null) {
                fetchReviews(stationId)
                // Refresh nearby stations list to show updated average rating
                lastFetchedLocation?.let { fetchNearbyStations(it, manual = false) }
                onSuccess()
            }
        }
    }

    fun fetchStationsAlongRoute(waypoints: String, connectorType: String?) {
        viewModelScope.launch {
            _isRouteLoading.value = true
            _routeStations.value = repository.getStationsAlongRoute(waypoints, connectorType)
            _isRouteLoading.value = false
        }
    }

    fun planRoute(from: String, to: String, connectorType: String?) {
        viewModelScope.launch {
            _isRouteLoading.value = true
            try {
                val response = repository.planRoute(from, to, connectorType)
                if (response != null) {
                    _routeStations.value = response.stations
                    _routePoints.value = response.routePoints.map { LatLng(it[0], it[1]) }
                    _routeDistanceKm.value = response.distanceKm
                    _routeDurationSec.value = response.durationSec
                    _routeFromName.value = response.fromName
                    _routeToName.value = response.toName
                    _routeError.value = null
                } else {
                    _routeStations.value = emptyList()
                    _routePoints.value = emptyList()
                    _routeDistanceKm.value = 0.0
                    _routeDurationSec.value = 0.0
                    _routeFromName.value = ""
                    _routeToName.value = ""
                    _routeError.value = "Failed to compute route. Please verify addresses."
                }
            } catch (e: Exception) {
                android.util.Log.e("ViewModel", "Error planning route", e)
                _routeStations.value = emptyList()
                _routePoints.value = emptyList()
                _routeDistanceKm.value = 0.0
                _routeDurationSec.value = 0.0
                _routeFromName.value = ""
                _routeToName.value = ""
                _routeError.value = "Error: ${e.localizedMessage ?: "Unknown error"}"
            } finally {
                _isRouteLoading.value = false
            }
        }
    }

    fun clearRoute() {
        _routeStations.value = emptyList()
        _routePoints.value = emptyList()
        _routeDistanceKm.value = 0.0
        _routeDurationSec.value = 0.0
        _routeFromName.value = ""
        _routeToName.value = ""
        _routeError.value = null
    }

    private val _uiState = MutableStateFlow<StationUiState>(StationUiState.Loading)
    val uiState: StateFlow<StationUiState> = _uiState.asStateFlow()

    var isManualSearch: Boolean = false
        private set

    fun fetchNearbyStations(location: LatLng, distance: Double = 20.0, manual: Boolean = false) {
        isManualSearch = manual
        // Cancel previous search if still running
        searchJob?.cancel()
        
        searchJob = viewModelScope.launch {
            _uiState.value = StationUiState.Loading
            try {
                val stations = repository.getNearbyStations(location.latitude, location.longitude, distance)
                _uiState.value = StationUiState.Success(stations)
                lastFetchedLocation = location
            } catch (e: Exception) {
                _uiState.value = StationUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun fetchNearbyStationsDebounced(location: LatLng, radius: Double) {
        // Only fetch if moved more than ~500 meters
        val distanceMoved = lastFetchedLocation?.let { 
            calculateDistance(it, location)
        } ?: Float.MAX_VALUE

        if (distanceMoved < 500f) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(1500) // 1.5 second debounce
            fetchNearbyStations(location, radius, manual = false)
        }
    }

    fun fetchNearbyStationsForZoom(location: LatLng, zoom: Float, manual: Boolean = false) {
        val radius = calculateRadiusFromZoom(zoom)
        fetchNearbyStations(location, radius, manual)
    }

    private fun calculateRadiusFromZoom(zoom: Float): Double {
        return when {
            zoom >= 15f -> 5.0
            zoom >= 12f -> 15.0
            zoom >= 10f -> 30.0
            zoom >= 8f -> 100.0
            zoom >= 6f -> 300.0
            zoom >= 4f -> 1000.0
            else -> 3000.0
        }
    }

    private fun calculateDistance(start: LatLng, end: LatLng): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            start.latitude, start.longitude,
            end.latitude, end.longitude,
            results
        )
        return results[0]
    }

    fun updateProfile(
        context: android.content.Context,
        newName: String,
        newAvatarUrl: String?,
        imageUri: android.net.Uri? = null,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val signedIn = authState.value is AuthState.SignedIn
            if (signedIn) {
                val current = (authState.value as AuthState.SignedIn).profile
                try {
                    var finalAvatarUrl = newAvatarUrl
                    if (imageUri != null) {
                        // Upload the local image to Supabase Storage and get the public URL
                        finalAvatarUrl = com.ganesh.stationfinder.data.network.AuthManager.uploadAvatar(context, imageUri)
                    }

                    // 1. Update in Supabase
                    com.ganesh.stationfinder.data.network.AuthManager.updateProfileInSupabase(newName, finalAvatarUrl)
                    
                    // 2. Update in Spring Boot Backend Database
                    val updated = repository.updateProfile(
                        current.copy(displayName = newName, avatarUrl = finalAvatarUrl)
                    )
                    
                    if (updated != null) {
                        // 3. Refresh local Auth State
                        refreshAuthState(context)
                        onSuccess()
                    } else {
                        onError("Failed to update profile in backend database.")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ViewModel", "Error updating profile", e)
                    onError(e.message ?: "Unknown error occurred")
                }
            } else {
                onError("User is not signed in.")
            }
        }
    }

    fun deleteAccount(
        context: android.content.Context,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val signedIn = authState.value is AuthState.SignedIn
            if (signedIn) {
                try {
                    val success = repository.deleteAccount()
                    if (success) {
                        signOut(context)
                        onSuccess()
                    } else {
                        onError("Failed to delete account from backend.")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ViewModel", "Error deleting account", e)
                    onError(e.message ?: "Unknown error occurred")
                }
            } else {
                onError("User is not signed in.")
            }
        }
    }
}
