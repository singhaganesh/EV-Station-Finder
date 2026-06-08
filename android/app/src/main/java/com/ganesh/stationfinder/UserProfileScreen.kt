package com.ganesh.stationfinder

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.ganesh.stationfinder.data.model.UserProfile

// Picker and Image imports
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import coil.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale

private val Teal = Color(0xFF0F766E)
private val Slate = Color(0xFF1E293B)
private val Bg = Color(0xFFF8FAFC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    viewModel: StationViewModel,
    onSignInClick: () -> Unit,
    onNavigateToVehicles: () -> Unit,
    onNavigateToSavedCount: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val authState by viewModel.authState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshAuthState(context)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Profile", fontWeight = FontWeight.ExtraBold, color = Slate) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.White),
                windowInsets = WindowInsets(0.dp)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Bg)
        ) {
            when (val state = authState) {
                is AuthState.Loading -> {
                    CircularProgressIndicator(
                        color = Teal,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is AuthState.SignedOut -> SignedOutContent(onSignInClick)
                is AuthState.SignedIn -> SignedInContent(
                    profile = state.profile,
                    viewModel = viewModel,
                    onNavigateToVehicles = onNavigateToVehicles,
                    onNavigateToSavedCount = onNavigateToSavedCount,
                    onNavigateToSettings = onNavigateToSettings,
                    onSignOut = { viewModel.signOut(context) }
                )
            }
        }
    }
}

@Composable
private fun SignedOutContent(onSignInClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(Color(0xFFE0F2F1), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Person, null, tint = Teal, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Sign in to sync everything", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Slate)
        Spacer(Modifier.height(8.dp))
        Text(
            "Save your favourites across devices, write trusted reviews, and keep your vehicles and route history in one place.",
            color = Color.Gray,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onSignInClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(27.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Teal)
        ) {
            Text("Sign in / Sign up", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
        }
    }
}

@Composable
private fun SignedInContent(
    profile: UserProfile,
    viewModel: StationViewModel,
    onNavigateToVehicles: () -> Unit,
    onNavigateToSavedCount: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    var showEditProfile by remember { mutableStateOf(false) }
    var showGoogleAccount by remember { mutableStateOf(false) }

    if (showEditProfile) {
        EditProfileDialog(
            profile = profile,
            onDismiss = { showEditProfile = false },
            onSave = { name, avatar, localUri ->
                viewModel.updateProfile(
                    context = context,
                    newName = name,
                    newAvatarUrl = avatar,
                    imageUri = localUri,
                    onSuccess = {
                        showEditProfile = false
                        android.widget.Toast.makeText(context, "Profile updated successfully!", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onError = { err ->
                        android.widget.Toast.makeText(context, "Error: $err", android.widget.Toast.LENGTH_LONG).show()
                    }
                )
            },
            onDeleteAccount = {
                viewModel.deleteAccount(
                    context = context,
                    onSuccess = {
                        showEditProfile = false
                        android.widget.Toast.makeText(context, "Account deleted successfully.", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onError = { err ->
                        android.widget.Toast.makeText(context, "Error: $err", android.widget.Toast.LENGTH_LONG).show()
                    }
                )
            }
        )
    }

    if (showGoogleAccount) {
        GoogleAccountDialog(
            profile = profile,
            onDismiss = { showGoogleAccount = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ProfileHeader(profile)

        ProfileSection("Account") {
            ProfileMenuRow(
                icon = Icons.Default.Person,
                title = "Edit profile",
                subtitle = "Name, photo",
                onClick = { showEditProfile = true }
            )
            ProfileMenuRow(
                icon = Icons.Default.AccountCircle,
                title = "Google account",
                subtitle = "Connected to Google Sign-In",
                onClick = { showGoogleAccount = true }
            )
        }

        ProfileSection("My EV") {
            ProfileMenuRow(
                Icons.Default.DirectionsCar,
                "My Vehicles",
                subtitle = "Manage your electric vehicles",
                onClick = onNavigateToVehicles
            )
            ProfileMenuRow(
                Icons.Default.Bolt,
                "Charging Preferences",
                subtitle = "Connector, power, filters",
                onClick = onNavigateToVehicles
            )
        }

        ProfileSection("My Activity") {
            ProfileMenuRow(Icons.Default.Bookmark, "Saved stations", onClick = onNavigateToSavedCount)
            ProfileMenuRow(Icons.Default.RateReview, "My reviews")
            ProfileMenuRow(Icons.Default.History, "Route history")
        }

        ProfileSection("Settings") {
            ProfileMenuRow(Icons.Default.Tune, "App settings", onClick = onNavigateToSettings)
            ProfileMenuRow(Icons.Default.Notifications, "Notifications")
        }

        ProfileSection("About") {
            ProfileMenuRow(Icons.Default.Help, "Help & support")
            ProfileMenuRow(Icons.Default.Policy, "Privacy policy")
            ProfileMenuRow(Icons.Default.Info, "Version", subtitle = "1.0.0", showChevron = false)
        }

        // Sign out
        OutlinedButton(
            onClick = onSignOut,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.5.dp, Color(0xFFEF4444)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
        ) {
            Icon(Icons.Default.Logout, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Sign out", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ProfileHeader(profile: UserProfile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color(0xFFE0F2F1), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (!profile.avatarUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = profile.avatarUrl,
                        contentDescription = "Avatar",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // User Initial
                    val initial = if (profile.displayName.isNotEmpty()) profile.displayName.take(1).uppercase() else "U"
                    Text(
                        text = initial,
                        color = Teal,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.displayName, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Slate)
                Text(
                    profile.email ?: "",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun ProfileSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun ProfileMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Teal, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, color = Slate, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Color.Gray)
        }
        if (showChevron && onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = Color.LightGray
            )
        }
    }
}

@Composable
fun EditProfileDialog(
    profile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (newName: String, newAvatarUrl: String?, localImageUri: android.net.Uri?) -> Unit,
    onDeleteAccount: () -> Unit
) {
    var name by remember { mutableStateOf(profile.displayName) }
    var avatarUrl by remember { mutableStateOf(profile.avatarUrl ?: "") }
    var selectedImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedImageUri = uri
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Account", fontWeight = FontWeight.Bold, color = Color(0xFFEF4444)) },
            text = { Text("Are you absolutely sure you want to delete your account? This action is permanent and cannot be undone. All your vehicles, preferences, and favorites will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteAccount()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Text("Delete Permanently", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Edit Profile", fontWeight = FontWeight.Bold, color = Slate) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Avatar picker/preview circle
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(Color(0xFFE0F2F1), CircleShape)
                            .clip(CircleShape)
                            .clickable {
                                pickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedImageUri != null) {
                            AsyncImage(
                                model = selectedImageUri,
                                contentDescription = "New Avatar Preview",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else if (!profile.avatarUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = profile.avatarUrl,
                                contentDescription = "Current Avatar",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            val initial = if (name.isNotEmpty()) name.take(1).uppercase() else "U"
                            Text(
                                text = initial,
                                color = Teal,
                                fontWeight = FontWeight.Bold,
                                fontSize = 32.sp
                            )
                        }
                        
                        // Edit icon overlay
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "Change photo",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    
                    Text(
                        text = "Tap circle to upload photo",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Teal,
                            focusedLabelColor = Teal
                        )
                    )
                    
                    OutlinedTextField(
                        value = if (selectedImageUri != null) "Local Image Selected" else avatarUrl,
                        onValueChange = { if (selectedImageUri == null) avatarUrl = it },
                        label = { Text("Avatar Image URL (Optional)") },
                        singleLine = true,
                        enabled = selectedImageUri == null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Teal,
                            focusedLabelColor = Teal
                        )
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    HorizontalDivider(color = Color(0xFFE2E8F0))
                    
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Delete Account", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onSave(name, if (avatarUrl.isBlank()) null else avatarUrl, selectedImageUri) },
                    colors = ButtonDefaults.buttonColors(containerColor = Teal),
                    enabled = name.isNotBlank()
                ) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Slate)
                }
            }
        )
    }
}

@Composable
fun GoogleAccountDialog(
    profile: UserProfile,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = Teal,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Google Connection", fontWeight = FontWeight.Bold, color = Slate)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Your profile is linked to your Google Account.",
                    fontWeight = FontWeight.Medium,
                    color = Slate
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Email Address: ${profile.email}",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
                Text(
                    text = "Authentication: Secure Google OAuth2",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
                Text(
                    text = "Provider ID: google.com",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Teal)
            ) {
                Text("Close")
            }
        }
    )
}
