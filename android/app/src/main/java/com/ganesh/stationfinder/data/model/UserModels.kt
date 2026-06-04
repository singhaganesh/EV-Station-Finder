package com.ganesh.stationfinder.data.model

data class UserProfile(
    val id: String,
    val displayName: String,
    val email: String?,
    val avatarUrl: String? = null
)

data class Vehicle(
    val id: String,
    val model: String,
    val batteryKwh: String,
    val rangeKm: String,
    val preferredConnector: String,
    val isPrimary: Boolean = false
)

data class UserVehicle(
    val id: Long? = null,
    val modelName: String,
    val batteryCapacity: String?,
    val rangeKm: String?,
    val preferredConnector: String?,
    val minPowerKw: Int?,
    val onlyOpen: Boolean?,
    val onlyAvailable: Boolean?
)
