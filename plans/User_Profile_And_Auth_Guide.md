# User Profile Redesign + Supabase Authentication Guide

This guide turns the current vehicle-only `ProfileScreen` into a proper **User Profile**, where the vehicle profile becomes one option *under* the user profile. It also covers integrating **Supabase Auth** end to end (Android + Spring Boot).

---

## Part 1 — The Concept

### Current state
`ProfileScreen.kt` is really a *vehicle* screen: model, battery, range, preferred connector, min power, two toggles — all stored locally in `SharedPreferences` via `FavoriteManager`. There is no concept of a user.

### Target state
A two-level structure:

```
UserProfileScreen  (the new top level)
├── [Header] avatar · display name · email/phone
├── Account            → name, email, phone, sign-in method, change password
├── My Vehicles        → VehicleProfileScreen   ← the old "profile" lives here
├── Charging Preferences → preferred connector, min power, only-open/available
├── My Activity        → saved stations · my reviews · route history
├── Settings           → units, notifications, theme, language
├── About & Support    → help, privacy, terms, app version
├── Sign out
└── Delete account
```

When **signed out**, the screen shows a sign-in CTA (and still lets the user edit a local vehicle profile, which migrates into their account on first sign-in).

### Information architecture

```
                    ┌───────────────────────┐
                    │   UserProfileScreen    │
                    │  (identity + account)  │
                    └───────────┬───────────┘
            ┌───────────────────┼───────────────────────┐
            ▼                   ▼                         ▼
   VehicleProfileScreen   MyReviewsScreen          SettingsScreen
   (one or many EVs)      (reviews you wrote)      (app preferences)
```

---

## Part 2 — Data Models

Create `data/model/UserModels.kt`:

```kotlin
package com.ganesh.stationfinder.data.model

// The authenticated user (identity comes from Supabase Auth via Google Sign-In)
data class UserProfile(
    val id: String,              // Supabase auth UID (UUID), the JWT "sub" claim
    val displayName: String,
    val email: String?,
    val avatarUrl: String? = null
)

// A vehicle belongs to a user. Supports multiple vehicles per account.
data class Vehicle(
    val id: String,
    val model: String,
    val batteryKwh: String,
    val rangeKm: String,
    val preferredConnector: String,
    val isPrimary: Boolean = false
)
```

---

## Part 3 — Auth State in the ViewModel

Add to `StationViewModel` (or a dedicated `AuthViewModel` if you prefer separation):

```kotlin
// Auth state model
sealed class AuthState {
    object Loading : AuthState()
    object SignedOut : AuthState()
    data class SignedIn(val profile: UserProfile) : AuthState()
}

// Inside StationViewModel:
private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
val authState: StateFlow<AuthState> = _authState.asStateFlow()

private val _vehicles = MutableStateFlow<List<Vehicle>>(emptyList())
val vehicles: StateFlow<List<Vehicle>> = _vehicles.asStateFlow()

fun refreshAuthState() {
    viewModelScope.launch {
        // Pull the current Supabase session (see Part 8)
        val profile = AuthManager.currentProfile()
        _authState.value = if (profile != null) {
            AuthState.SignedIn(profile)
        } else {
            AuthState.SignedOut
        }
    }
}

fun signOut() {
    viewModelScope.launch {
        AuthManager.signOut()
        _authState.value = AuthState.SignedOut
    }
}

fun loadVehicles() {
    viewModelScope.launch {
        // Once backend is ready, fetch from /api/me/vehicles
        // For now you can bridge to FavoriteManager's single vehicle
        _vehicles.value = repository.getMyVehicles()
    }
}
```

---

## Part 4 — The new `UserProfileScreen`

Create `UserProfileScreen.kt`:

```kotlin
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
import com.ganesh.stationfinder.data.model.UserProfile

private val Teal = Color(0xFF0F766E)
private val Slate = Color(0xFF1E293B)
private val Bg = Color(0xFFF8FAFC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    viewModel: StationViewModel,
    onSignInClick: () -> Unit,
    onNavigateToVehicles: () -> Unit,
    onNavigateToReviews: () -> Unit,
    onNavigateToSavedCount: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val authState by viewModel.authState.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshAuthState() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Profile", fontWeight = FontWeight.ExtraBold, color = Slate) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.White)
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
                    onNavigateToVehicles = onNavigateToVehicles,
                    onNavigateToReviews = onNavigateToReviews,
                    onNavigateToSavedCount = onNavigateToSavedCount,
                    onNavigateToSettings = onNavigateToSettings,
                    onSignOut = { viewModel.signOut() }
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
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(27.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Teal)
        ) {
            Text("Sign in / Sign up", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
private fun SignedInContent(
    profile: UserProfile,
    onNavigateToVehicles: () -> Unit,
    onNavigateToReviews: () -> Unit,
    onNavigateToSavedCount: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSignOut: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ProfileHeader(profile)

        ProfileSection("Account") {
            ProfileMenuRow(Icons.Default.Person, "Edit profile", subtitle = "Name, photo")
            ProfileMenuRow(
                Icons.Default.VpnKey,
                "Google account",
                subtitle = "Connected to your Google Sign-In"
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
                onClick = onNavigateToVehicles  // or a dedicated screen
            )
        }

        ProfileSection("My Activity") {
            ProfileMenuRow(Icons.Default.Bookmark, "Saved stations", onClick = onNavigateToSavedCount)
            ProfileMenuRow(Icons.Default.RateReview, "My reviews", onClick = onNavigateToReviews)
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
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.5.dp, Color(0xFFEF4444)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
        ) {
            Icon(Icons.Default.Logout, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Sign out", fontWeight = FontWeight.Bold)
        }

        Text(
            "Delete account",
            color = Color.Gray,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable { /* show confirm dialog → DELETE /api/me */ }
                .padding(8.dp)
        )
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
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(64.dp).background(Color(0xFFE0F2F1), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, null, tint = Teal, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.displayName, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Slate)
                Text(
                    profile.email ?: profile.phone ?: "",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            }
            IconButton(onClick = { /* edit */ }) {
                Icon(Icons.Default.Edit, "Edit", tint = Teal)
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
```

---

## Part 5 — The `VehicleProfileScreen` (your old screen, refactored)

Rename the existing `ProfileScreen.kt` to `VehicleProfileScreen.kt`. Keep its vehicle fields and charging preferences almost as-is — it's now a sub-screen reached from "My Vehicles". Two small changes:

1. The back button returns to the user profile (not the map).
2. Save persists to the backend (`PUT /api/me/vehicles`) once auth exists; until then it can keep using `FavoriteManager` as a local cache.

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleProfileScreen(
    viewModel: StationViewModel,
    onBackClick: () -> Unit
) {
    // ... exactly your current ProfileScreen body ...
    // topBar title → "My Vehicle"
    // navigationIcon back → onBackClick()  (returns to UserProfileScreen)
    // On SAVE: call viewModel.saveVehicleProfile(...) which writes to backend + local cache
}
```

**Enhancement (optional):** support multiple vehicles. Show a list of vehicle cards with a "+ Add vehicle" button; tapping one opens this editor. Mark one as primary; the route planner uses the primary vehicle's connector/range.

---

## Part 6 — Navigation wiring

In `MainActivity.kt`, update `NavigationItem` and the `NavHost`:

```kotlin
sealed class NavigationItem(val route: String, val icon: ImageVector, val label: String) {
    object Map : NavigationItem("map", Icons.Default.Map, "Map")
    object List : NavigationItem("list", Icons.AutoMirrored.Filled.List, "List")
    object RoutePlan : NavigationItem("route_planner", Icons.Default.Navigation, "Route Plan")
    object Saved : NavigationItem("saved", Icons.Default.Bookmark, "Saved")
    object Profile : NavigationItem("profile", Icons.Default.Person, "Profile")
}

// Non-bottom-bar routes (sub-screens)
private const val ROUTE_VEHICLES = "vehicles"
private const val ROUTE_MY_REVIEWS = "my_reviews"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_SIGN_IN = "sign_in"

// In the NavHost:
composable(NavigationItem.Profile.route) {
    UserProfileScreen(
        viewModel = viewModel,
        onSignInClick = { navController.navigate(ROUTE_SIGN_IN) },
        onNavigateToVehicles = { navController.navigate(ROUTE_VEHICLES) },
        onNavigateToReviews = { navController.navigate(ROUTE_MY_REVIEWS) },
        onNavigateToSavedCount = { navController.navigate(NavigationItem.Saved.route) },
        onNavigateToSettings = { navController.navigate(ROUTE_SETTINGS) }
    )
}
composable(ROUTE_VEHICLES) {
    VehicleProfileScreen(viewModel = viewModel, onBackClick = { navController.popBackStack() })
}
composable(ROUTE_SIGN_IN) {
    SignInScreen(viewModel = viewModel, onSignedIn = { navController.popBackStack() })
}
// ... ROUTE_MY_REVIEWS, ROUTE_SETTINGS similar
```

The bottom nav stays 5 tabs; "Profile" now lands on `UserProfileScreen`. The vehicle screen and sign-in screen are pushed on top (no bottom bar — your `showBottomBar` check already hides it for non-listed routes).

---

## Part 7 — Backend changes (so profile data actually syncs)

Right now favourites and the vehicle profile are device-local. With accounts, they move server-side keyed by the Supabase user UUID.

### New entity: `AppUser`
```java
@Entity
@Table(name = "app_users")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AppUser {
    @Id
    private String id;            // Supabase auth UID (UUID string, the JWT "sub")
    private String displayName;
    private String email;
    private String phone;
    private String avatarUrl;
    private LocalDateTime createdAt;
}
```

### New entity: `Favorite`
```java
@Entity
@Table(name = "favorites",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "station_id"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Favorite {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id") private String userId;     // AppUser.id
    @Column(name = "station_id") private Long stationId;
    private LocalDateTime createdAt;
}
```

### Optional: `UserVehicle` table
```java
@Entity @Table(name = "user_vehicles")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserVehicle {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id") private String userId;
    private String model;
    private String batteryKwh;
    private String rangeKm;
    private String preferredConnector;
    private boolean primary;
}
```

### Link reviews to a user
Add `private String userId;` to `Review`, and derive it from the authenticated token (not the request body). This makes ratings trustworthy and lets you show "My reviews".

### New `/api/me/*` endpoints (all require auth)
```
GET    /api/me/profile          → current user's profile
PUT    /api/me/profile          → update display name / avatar
GET    /api/me/vehicles         → list vehicles
POST   /api/me/vehicles         → add a vehicle
PUT    /api/me/vehicles/{id}    → update a vehicle
DELETE /api/me/vehicles/{id}    → remove a vehicle
GET    /api/me/favorites        → list favourite station IDs (or full stations)
POST   /api/me/favorites/{id}   → add favourite
DELETE /api/me/favorites/{id}   → remove favourite
GET    /api/me/reviews          → reviews this user wrote
DELETE /api/me                  → delete account (app-store requirement)
```

The current device-local favourites can be migrated on first sign-in: read the `SharedPreferences` set, `POST` each to `/api/me/favorites`, then clear it.

---

## Part 8 — Supabase Auth integration

### Why Supabase Auth with Google Sign-In (for your stack)
You already have Supabase. Its Auth service (GoTrue) integrates with Google OAuth and stores users in the **same Postgres** as your app data. Sign-in is as simple as tapping a Google button; users authenticate with their Google account, and your Spring Boot backend validates the JWT. One vendor, one identity store.

| Option | Setup time | Simplicity | Fit for you |
|---|---|---|---|
| **Supabase Auth + Google** | 30 minutes | Very simple: one button, one OAuth provider | **Best** — same DB, fast to ship, Google is ubiquitous |
| Firebase Auth + Google | 1 hour | Simple, but adds a second vendor | Works well, but couples you to Firebase |
| Email/password + Supabase Auth | 2 hours | Medium: password reset, email verification | More work, not needed if Google is enough |

### A. Android side (`supabase-kt`)

Add the dependency (BOM + Auth):
```kotlin
implementation(platform("io.github.jan-tennert.supabase:bom:<latest>"))
implementation("io.github.jan-tennert.supabase:auth-kt")
implementation("io.ktor:ktor-client-android:<latest>")
```

Create a Supabase client + auth helper:
```kotlin
object SupabaseProvider {
    val client = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,        // add to local.properties → BuildConfig
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    ) {
        install(Auth)
    }
}

object AuthManager {
    private val auth get() = SupabaseProvider.client.auth

    suspend fun signInWithGoogle() = auth.signInWith(Google)

    fun currentAccessToken(): String? = auth.currentAccessTokenOrNull()

    suspend fun currentProfile(): UserProfile? {
        val user = auth.currentUserOrNull() ?: return null
        return UserProfile(
            id = user.id,
            displayName = user.userMetadata?.get("name")?.toString() ?: "User",
            email = user.email,
            avatarUrl = user.userMetadata?.get("picture")?.toString()
        )
    }

    suspend fun signOut() = auth.signOut()
}
```

In the **Supabase dashboard**, go to **Authentication → Providers** and enable **Google** only (you can disable/ignore Email, Phone, Magic Link, etc.). Then create a Google OAuth credential in Google Cloud Console and link it in the Supabase settings.

In your `SignInScreen`, just one button:
```kotlin
Button(
    onClick = { viewModel.signInWithGoogle() },
    modifier = Modifier.fillMaxWidth().height(54.dp),
    shape = RoundedCornerShape(27.dp),
    colors = ButtonDefaults.buttonColors(containerColor = Teal)
) {
    Icon(Icons.Default.Google, null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text("Sign in with Google", fontWeight = FontWeight.Bold)
}
```

Attach the token to every backend call — update `RetrofitClient.kt`:
```kotlin
private val authInterceptor = okhttp3.Interceptor { chain ->
    val token = AuthManager.currentAccessToken()
    val request = chain.request().newBuilder().apply {
        if (token != null) header("Authorization", "Bearer $token")
    }.build()
    chain.proceed(request)
}

private val httpClient = OkHttpClient.Builder()
    .addInterceptor(authInterceptor)
    .addInterceptor(logging)            // remember: NONE in release builds
    // ... existing config
    .build()
```

### B. Spring Boot side (validate the Supabase JWT)

Add the resource-server dependency:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

**Option 1 — JWKS (asymmetric keys, recommended).** If your Supabase project uses asymmetric signing keys, point Spring at the JWKS endpoint:
```properties
spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json
spring.security.oauth2.resourceserver.jwt.issuer-uri=https://<project-ref>.supabase.co/auth/v1
```

**Option 2 — Shared secret (HS256, classic).** If your tokens are HS256-signed with the project JWT secret, define a decoder:
```java
@Bean
public JwtDecoder jwtDecoder(@Value("${supabase.jwt-secret}") String secret) {
    SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
            .macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(
            "https://<project-ref>.supabase.co/auth/v1"));
    return decoder;
}
```

Security config — protect everything, expose only public reads, lock admin endpoints:
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Public read endpoints
                .requestMatchers(HttpMethod.GET,
                    "/api/stations/nearby", "/api/stations/viewport",
                    "/api/stations/*/detail", "/api/stations/search",
                    "/api/stations/*/reviews", "/api/stations/route/**").permitAll()
                // Admin-only destructive endpoints
                .requestMatchers("/api/import/**", "/api/stations/cleanup-duplicates")
                    .hasRole("ADMIN")
                // Everything user-scoped requires auth
                .requestMatchers("/api/me/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/stations/*/reviews").authenticated()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
```

Resolve the current user in controllers via the JWT `sub` claim:
```java
@GetMapping("/api/me/profile")
public ResponseEntity<ApiResponse<?>> getMyProfile(@AuthenticationPrincipal Jwt jwt) {
    String userId = jwt.getSubject();          // Supabase auth UID
    AppUser user = appUserService.getOrCreate(jwt);  // lazily create on first call
    return ResponseEntity.ok(ApiResponse.success("OK", user));
}
```

`getOrCreate` reads `sub`, `email`, and any `user_metadata` name from the JWT and upserts an `AppUser` row.

### C. Migrate existing local data on first sign-in
On the first successful sign-in:
1. Read the `SharedPreferences` favourite IDs and vehicle profile.
2. `POST` each favourite to `/api/me/favorites` and the vehicle to `/api/me/vehicles`.
3. Clear the local copies (or keep them as an offline cache).

---

## Implementation order

1. **Backend auth first**: add the resource server + `SecurityConfig`, lock down the destructive endpoints, add `AppUser` + `getOrCreate`.
2. **Android auth**: add `supabase-kt`, `AuthManager`, the OkHttp interceptor, and a `SignInScreen`.
3. **User profile UI**: add `UserProfileScreen`, rename `ProfileScreen` → `VehicleProfileScreen`, wire navigation.
4. **Server-side profile data**: add `Favorite`/`UserVehicle` tables and `/api/me/*` endpoints; migrate local data.
5. **Polish**: multiple vehicles, route history, settings screen, delete-account flow.

---

*End of guide.*
