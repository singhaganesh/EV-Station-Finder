package com.ganesh.stationfinder

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ganesh.stationfinder.data.model.OCMStation
import com.ganesh.stationfinder.util.FavoriteManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import androidx.compose.animation.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlannerScreen(
    viewModel: StationViewModel,
    onBackClick: () -> Unit,
    onStationClick: (OCMStation) -> Unit
) {
    val context = LocalContext.current
    val routeStations by viewModel.routeStations.collectAsState()
    val isRouteLoading by viewModel.isRouteLoading.collectAsState()
    val routePoints by viewModel.routePoints.collectAsState()
    val routeDistanceKm by viewModel.routeDistanceKm.collectAsState()
    val routeDurationSec by viewModel.routeDurationSec.collectAsState()
    val routeFromName by viewModel.routeFromName.collectAsState()
    val routeToName by viewModel.routeToName.collectAsState()
    val routeError by viewModel.routeError.collectAsState()

    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }

    val vehicleModel = remember { FavoriteManager.getVehicleModel(context) }
    val preferredConnector = remember { FavoriteManager.getPreferredConnector(context) }
    val range = remember { FavoriteManager.getRange(context) }

    // Clear route on initial entry to ensure step-by-step flow from a clean state
    LaunchedEffect(Unit) {
        viewModel.clearRoute()
    }

    // Monitor and show route error toasts
    LaunchedEffect(routeError) {
        routeError?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearRouteError()
        }
    }

    // Map properties
    // Mumbai center coordinates
    val defaultCenter = LatLng(18.85, 73.25)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(defaultCenter, 9f)
    }

    // Center camera to fit bounds of routePoints when loaded
    LaunchedEffect(routePoints) {
        if (routePoints.isNotEmpty()) {
            val builder = com.google.android.gms.maps.model.LatLngBounds.builder()
            routePoints.forEach { builder.include(it) }
            val bounds = builder.build()
            try {
                cameraPositionState.animate(
                    update = CameraUpdateFactory.newLatLngBounds(bounds, 150),
                    durationMs = 1000
                )
            } catch (e: Exception) {
                cameraPositionState.position = CameraPosition.fromLatLngZoom(bounds.center, 8f)
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Route Planner",
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF1E293B)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFF0F766E))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Google Map View (renders underneath panels)
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                uiSettings = MapUiSettings(zoomControlsEnabled = false)
            ) {
                // Draw route line
                if (routePoints.isNotEmpty()) {
                    Polyline(
                        points = routePoints,
                        color = Color(0xFF0F766E),
                        width = 8f
                    )

                    // Route pins (using premium custom teardrop pins)
                    Marker(
                        state = MarkerState(position = routePoints.first()),
                        title = "Start: ${if (routeFromName.isNotEmpty()) routeFromName else fromText}",
                        icon = MarkerIconCache.get("#3B82F6", isLarge = true)
                    )
                    Marker(
                        state = MarkerState(position = routePoints.last()),
                        title = "End: ${if (routeToName.isNotEmpty()) routeToName else toText}",
                        icon = MarkerIconCache.get("#EF4444", isLarge = true)
                    )
                }

                // Recommended charging stops along the route (using premium custom teardrop pins)
                routeStations.forEachIndexed { index, station ->
                    val isAvailable = (station.availableSlots ?: 0) > 0
                    Marker(
                        state = MarkerState(
                            position = LatLng(station.latitude, station.longitude)
                        ),
                        title = "Stop ${index + 1}: ${station.name}",
                        snippet = if (isAvailable) "Available" else "Busy",
                        icon = MarkerIconCache.get(
                            if (isAvailable) "#10B981" else "#F97316",
                            isLarge = false
                        ),
                        onClick = {
                            onStationClick(station)
                            true
                        }
                    )
                }
            }

            // Route Input overlay card (positioned at the top)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Input fields
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = fromText,
                                    onValueChange = { fromText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Starting point") },
                                    placeholder = { Text("Enter starting address") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.MyLocation,
                                            contentDescription = null,
                                            tint = Color(0xFF0F766E),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    trailingIcon = {
                                        if (fromText.isNotEmpty()) {
                                            IconButton(onClick = { fromText = "" }) {
                                                Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF0F766E),
                                        unfocusedBorderColor = Color(0xFFE2E8F0),
                                        focusedContainerColor = Color(0xFFF8FAFC),
                                        unfocusedContainerColor = Color(0xFFF8FAFC)
                                    )
                                )
                                OutlinedTextField(
                                    value = toText,
                                    onValueChange = { toText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Destination") },
                                    placeholder = { Text("Enter destination address") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Place,
                                            contentDescription = null,
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    trailingIcon = {
                                        if (toText.isNotEmpty()) {
                                            IconButton(onClick = { toText = "" }) {
                                                Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF0F766E),
                                        unfocusedBorderColor = Color(0xFFE2E8F0),
                                        focusedContainerColor = Color(0xFFF8FAFC),
                                        unfocusedContainerColor = Color(0xFFF8FAFC)
                                    )
                                )
                            }

                            // Swap button
                            IconButton(onClick = {
                                val temp = fromText
                                fromText = toText
                                toText = temp
                            }) {
                                Icon(Icons.Default.SwapVert, contentDescription = "Swap", tint = Color.Gray)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Info chips (Model, connector, range)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AssistChip(
                                onClick = {},
                                label = { Text(vehicleModel, fontSize = 10.sp, fontWeight = FontWeight.Medium) },
                                leadingIcon = { Icon(Icons.Default.DirectionsCar, null, modifier = Modifier.size(12.dp), tint = Color(0xFF0F766E)) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = Color(0xFFF1F5F9),
                                    labelColor = Color(0xFF334155)
                                ),
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                            )
                            AssistChip(
                                onClick = {},
                                label = { Text(preferredConnector, fontSize = 10.sp, fontWeight = FontWeight.Medium) },
                                leadingIcon = { Icon(Icons.Default.EvStation, null, modifier = Modifier.size(12.dp), tint = Color(0xFF0F766E)) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = Color(0xFFF1F5F9),
                                    labelColor = Color(0xFF334155)
                                ),
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                            )
                            AssistChip(
                                onClick = {},
                                label = { Text(range, fontSize = 10.sp, fontWeight = FontWeight.Medium) },
                                leadingIcon = { Icon(Icons.Default.BatteryChargingFull, null, modifier = Modifier.size(12.dp), tint = Color(0xFF0F766E)) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = Color(0xFFF1F5F9),
                                    labelColor = Color(0xFF334155)
                                ),
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val canSearch = fromText.isNotBlank() && toText.isNotBlank()
                            if (routePoints.isEmpty()) {
                                Button(
                                    onClick = { viewModel.planRoute(fromText, toText, preferredConnector) },
                                    enabled = canSearch && !isRouteLoading,
                                    modifier = Modifier.fillMaxWidth().height(46.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF0F766E),
                                        contentColor = Color.White,
                                        disabledContainerColor = Color(0xFFCBD5E1),
                                        disabledContentColor = Color(0xFF94A3B8)
                                    )
                                ) {
                                    if (isRouteLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Search Route", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.planRoute(fromText, toText, preferredConnector) },
                                    enabled = canSearch && !isRouteLoading,
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF0F766E),
                                        contentColor = Color.White,
                                        disabledContainerColor = Color(0xFFCBD5E1),
                                        disabledContentColor = Color(0xFF94A3B8)
                                    )
                                ) {
                                    if (isRouteLoading) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Recalculate", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                }
                                
                                OutlinedButton(
                                    onClick = { viewModel.clearRoute() },
                                    modifier = Modifier.height(46.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.5.dp, Color(0xFFEF4444)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                                ) {
                                    Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Clear", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Panel: Suggested Stops list (slidable overlay, animated visibility)
            AnimatedVisibility(
                visible = routePoints.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Panel Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Suggested Stops",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1E293B)
                                )
                                val formattedDistance = if (routeDistanceKm > 0.0) "${routeDistanceKm} km" else "145 km"
                                val formattedDuration = if (routeDurationSec > 0.0) {
                                    val hours = (routeDurationSec / 3600).toInt()
                                    val minutes = ((routeDurationSec % 3600) / 60).toInt()
                                    if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
                                } else "3h 15m"
                                Text(
                                    text = "$formattedDistance • $formattedDuration total route estimation",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                            
                            Button(
                                onClick = {
                                    viewModel.planRoute(fromText, toText, preferredConnector)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE0F2F1), contentColor = Color(0xFF0F766E)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                if (isRouteLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color(0xFF0F766E))
                                } else {
                                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Refresh", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Suggested Stops timeline list
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (routeStations.isNotEmpty()) {
                                routeStations.forEachIndexed { index, station ->
                                    val isAvailable = (station.availableSlots ?: 0) > 0
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onStationClick(station) },
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Stop ${index + 1}: ${station.name}",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp,
                                                    color = Color(0xFF1E293B)
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .background(
                                                            if (isAvailable) Color(0xFFECFDF5) else Color(0xFFFEF2F2),
                                                            RoundedCornerShape(12.dp)
                                                        )
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text(
                                                        text = if (isAvailable) "Available" else "Busy",
                                                        color = if (isAvailable) Color(0xFF15803D) else Color(0xFF991B1B),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 9.sp
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = station.address ?: "",
                                                    fontSize = 11.sp,
                                                    color = Color.Gray
                                                )
                                                station.slots?.firstOrNull()?.let { slot ->
                                                    val formattedPower = slot.powerKw?.let { p ->
                                                        val rounded = Math.round(p * 10.0) / 10.0
                                                        if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
                                                    } ?: ""
                                                    Text(
                                                        text = "${slot.connectorType} • ${formattedPower}kW",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Color(0xFF0F766E)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No compatible stations along this route.",
                                        color = Color.Gray,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        // Navigation Action
                        Button(
                            onClick = {
                                if (routePoints.isNotEmpty()) {
                                    val dest = routePoints.last()
                                    val gmmIntentUri = Uri.parse("google.navigation:q=${dest.latitude},${dest.longitude}")
                                    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                                    mapIntent.setPackage("com.google.android.apps.maps")
                                    context.startActivity(mapIntent)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Icon(Icons.Default.Navigation, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start Navigation", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
