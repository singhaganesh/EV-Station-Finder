package com.ganesh.stationfinder.util

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource

object LocationHelper {

    /**
     * Resolve the device location. Tries the cached last-known location first; if
     * that is null (common on a fresh device / emulator), actively requests a
     * current fix. Calls back with null only if both fail, so the caller can show
     * a graceful fallback rather than hanging on a spinner.
     */
    @SuppressLint("MissingPermission")
    fun getCurrentLocation(context: Context, callback: (LatLng?) -> Unit) {
        val client = LocationServices.getFusedLocationProviderClient(context)

        client.lastLocation
            .addOnSuccessListener { last ->
                if (last != null) {
                    callback(LatLng(last.latitude, last.longitude))
                } else {
                    // No cached fix -- request a fresh one.
                    val cts = CancellationTokenSource()
                    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                        .addOnSuccessListener { current ->
                            callback(if (current != null) LatLng(current.latitude, current.longitude) else null)
                        }
                        .addOnFailureListener { callback(null) }
                }
            }
            .addOnFailureListener { callback(null) }
    }
}
